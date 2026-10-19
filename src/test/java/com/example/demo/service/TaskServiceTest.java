package com.example.demo.service;

import com.example.demo.model.TaskStatus;
import com.example.demo.model.dto.CreateTaskRequest;
import com.example.demo.model.dto.PageResponse;
import com.example.demo.model.dto.TaskResponse;
import com.example.demo.mq.TaskTriggerPublisher;
import com.example.demo.repository.ScheduledTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service rules against the real MySQL. Each test runs in a transaction that
 * is rolled back, so it can empty the table (list results depend on every
 * task) without touching anyone else's data.
 */
@SpringBootTest(properties = "app.scheduler.enabled=false")
@Transactional
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ScheduledTaskRepository repository;

    @Autowired
    private Clock clock;

    /** Unused here; declared so this class shares the cached context of the other service tests. */
    @MockitoBean
    private TaskTriggerPublisher publisher;

    @BeforeEach
    void emptyTable() {
        repository.deleteAllInBatch();
    }

    private TaskResponse create(String taskId, Instant executeAt) {
        return taskService.create(new CreateTaskRequest(taskId, executeAt, Map.of("type", "email")));
    }

    private Instant inAnHour() {
        return clock.instant().plusSeconds(3600);
    }

    @Test
    void duplicateTaskIdIsConflict() {
        create("svc-dup", inAnHour());

        assertThatThrownBy(() -> create("svc-dup", inAnHour().plusSeconds(60)))
                .isInstanceOf(TaskConflictException.class)
                .hasMessage("Task svc-dup already exists");
    }

    @Test
    void cancellingAnUnknownTaskIsNotFound() {
        assertThatThrownBy(() -> taskService.cancel("svc-nope"))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessage("Task svc-nope not found");
    }

    @Test
    void cancellingTwiceIsConflict() {
        create("svc-twice", inAnHour());
        assertThat(taskService.cancel("svc-twice").status()).isEqualTo(TaskStatus.CANCELLED);

        assertThatThrownBy(() -> taskService.cancel("svc-twice"))
                .isInstanceOf(TaskConflictException.class)
                .hasMessage("Task svc-twice is already cancelled and cannot be cancelled");
    }

    /** Ordered by executeAt, then by creation (id) for equal times, so pages never overlap. */
    @Test
    void listFiltersByStatusAndPagesInExecutionOrder() {
        Instant base = inAnHour();
        create("svc-c", base.plusSeconds(2));
        create("svc-a", base);
        create("svc-b2", base.plusSeconds(1));
        create("svc-b1", base.plusSeconds(1));
        create("svc-cancelled", base);
        taskService.cancel("svc-cancelled");

        PageResponse<TaskResponse> first = taskService.list(TaskStatus.PENDING, 0, 3);
        assertThat(first.content()).extracting(TaskResponse::taskId).containsExactly("svc-a", "svc-b2", "svc-b1");
        assertThat(first.totalElements()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);

        PageResponse<TaskResponse> second = taskService.list(TaskStatus.PENDING, 1, 3);
        assertThat(second.content()).extracting(TaskResponse::taskId).containsExactly("svc-c");

        assertThat(taskService.list(TaskStatus.CANCELLED, 0, 10).content())
                .extracting(TaskResponse::taskId).containsExactly("svc-cancelled");
    }
}
