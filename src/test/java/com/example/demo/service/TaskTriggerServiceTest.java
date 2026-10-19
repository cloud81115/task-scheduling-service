package com.example.demo.service;

import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import com.example.demo.mq.TaskTriggerMessage;
import com.example.demo.mq.TaskTriggerPublisher;
import com.example.demo.repository.ScheduledTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessagingException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Runs the real transaction and row locks against MySQL; only the RocketMQ
 * publisher is mocked so tests control whether publishing succeeds.
 *
 * <p>Not rolled back like @DataJpaTest: trigger() commits its own transaction,
 * so the tests delete only their own rows (prefix "trig-") instead of the table.
 */
@SpringBootTest(properties = "app.scheduler.enabled=false")
class TaskTriggerServiceTest {

    private static final Map<String, Object> PAYLOAD = Map.of("type", "email", "message", "hi");

    @Autowired
    private TaskTriggerService triggerService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ScheduledTaskRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @MockitoBean
    private TaskTriggerPublisher publisher;

    @BeforeEach
    @AfterEach
    void deleteTestRows() {
        jdbcTemplate.update("delete from scheduled_task where task_id like 'trig-%'");
    }

    private void saveDueTask(String taskId) {
        Instant now = clock.instant();
        repository.saveAndFlush(new ScheduledTask(taskId, now.minusSeconds(1), PAYLOAD, now));
    }

    private ScheduledTask reload(String taskId) {
        return repository.findByTaskId(taskId).orElseThrow();
    }

    @Test
    void publishesThenMarksTriggered() {
        saveDueTask("trig-ok");

        assertThat(triggerService.trigger("trig-ok")).isEqualTo(TriggerOutcome.TRIGGERED);

        ArgumentCaptor<TaskTriggerMessage> sent = ArgumentCaptor.forClass(TaskTriggerMessage.class);
        verify(publisher).publish(sent.capture());
        assertThat(sent.getValue().taskId()).isEqualTo("trig-ok");
        assertThat(sent.getValue().payload()).isEqualTo(PAYLOAD);

        ScheduledTask task = reload("trig-ok");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TRIGGERED);
        assertThat(task.getTriggeredAt()).isEqualTo(sent.getValue().triggeredAt());
    }

    @Test
    void failedPublishLeavesTaskPendingForRetry() {
        saveDueTask("trig-fail");
        doThrow(new MessagingException("broker down")).when(publisher).publish(any());

        assertThat(triggerService.trigger("trig-fail")).isEqualTo(TriggerOutcome.SEND_FAILED);

        ScheduledTask task = reload("trig-fail");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getTriggeredAt()).isNull();
    }

    @Test
    void nonPendingTasksAreSkippedWithoutPublishing() {
        saveDueTask("trig-cancelled");
        taskService.cancel("trig-cancelled");

        assertThat(triggerService.trigger("trig-cancelled")).isEqualTo(TriggerOutcome.SKIPPED);
        assertThat(triggerService.trigger("trig-unknown")).isEqualTo(TriggerOutcome.SKIPPED);
        verifyNoInteractions(publisher);
    }

    /** Another poller holds the row lock: SKIP LOCKED makes this one skip instead of waiting. */
    @Test
    void taskLockedByAnotherPollerIsSkipped() throws Exception {
        saveDueTask("trig-locked");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> otherPoller = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    assertThat(repository.lockPendingByTaskId("trig-locked")).isPresent();
                    locked.countDown();
                    await(release);
                }));
        try {
            await(locked);
            assertThat(triggerService.trigger("trig-locked")).isEqualTo(TriggerOutcome.SKIPPED);
            verifyNoInteractions(publisher);
        } finally {
            release.countDown();
            otherPoller.get(5, TimeUnit.SECONDS);
        }
        assertThat(reload("trig-locked").getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    /**
     * A cancel arriving while the task is being published waits for the row
     * lock, then sees TRIGGERED and is rejected: the task is never both sent
     * and cancelled.
     */
    @Test
    void cancelDuringPublishWaitsThenConflicts() throws Exception {
        saveDueTask("trig-race");
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch finishPublish = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            await(finishPublish);
            return null;
        }).when(publisher).publish(any());

        CompletableFuture<TriggerOutcome> trigger = CompletableFuture.supplyAsync(() -> triggerService.trigger("trig-race"));
        await(publishing);
        CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> taskService.cancel("trig-race"));

        Thread.sleep(500);
        assertThat(cancel).as("cancel should be blocked on the row lock").isNotDone();

        finishPublish.countDown();
        assertThat(trigger.get(5, TimeUnit.SECONDS)).isEqualTo(TriggerOutcome.TRIGGERED);
        assertThatThrownBy(() -> cancel.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TaskConflictException.class)
                .hasMessageContaining("already triggered");
        assertThat(reload("trig-race").getStatus()).isEqualTo(TaskStatus.TRIGGERED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
