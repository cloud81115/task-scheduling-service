package com.example.demo.service;

import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import com.example.demo.model.dto.TaskResponse;
import com.example.demo.mq.TaskTriggerPublisher;
import com.example.demo.repository.ScheduledTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Runs against the real Redis and MySQL; only the RocketMQ publisher is mocked.
 * Tests use the prefix "cache-" and clean up their own rows and cache entries.
 */
@SpringBootTest(properties = "app.scheduler.enabled=false")
class TaskCacheTest {

    private static final String KEY_PREFIX = "tasks::";
    private static final Map<String, Object> PAYLOAD = Map.of("type", "email", "message", "hi");

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskTriggerService triggerService;

    @Autowired
    private ScheduledTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @MockitoBean
    private TaskTriggerPublisher publisher;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from scheduled_task where task_id like 'cache-%'");
        Set<String> keys = redis.keys(KEY_PREFIX + "cache-*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void saveTask(String taskId, Instant executeAt) {
        repository.saveAndFlush(new ScheduledTask(taskId, executeAt, PAYLOAD, clock.instant()));
    }

    private String cached(String taskId) {
        return redis.opsForValue().get(KEY_PREFIX + taskId);
    }

    @Test
    void getStoresTheResponseAsJsonWithTtl() throws Exception {
        saveTask("cache-json", clock.instant().plusSeconds(60));

        taskService.get("cache-json");

        JsonNode json = objectMapper.readTree(cached("cache-json"));
        assertThat(json.get("taskId").asText()).isEqualTo("cache-json");
        assertThat(json.get("status").asText()).isEqualTo("pending");
        assertThat(json.get("executeAt").isTextual()).as("ISO-8601 string, not a number").isTrue();
        assertThat(json.has("@class")).isFalse();
        assertThat(redis.getExpire(KEY_PREFIX + "cache-json")).isBetween(1L, 600L);
    }

    @Test
    void secondGetIsServedFromCache() {
        saveTask("cache-hit", clock.instant().plusSeconds(60));
        TaskResponse first = taskService.get("cache-hit");

        // Change the row behind the cache's back: a cached read does not see it.
        jdbcTemplate.update("update scheduled_task set status = 'CANCELLED' where task_id = 'cache-hit'");

        TaskResponse second = taskService.get("cache-hit");
        assertThat(second).as("read back from Redis unchanged").isEqualTo(first);
        assertThat(second.status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void notFoundIsNotCached() {
        assertThatThrownBy(() -> taskService.get("cache-missing")).isInstanceOf(TaskNotFoundException.class);
        assertThat(cached("cache-missing")).isNull();
    }

    @Test
    void cancelEvicts() {
        saveTask("cache-cancel", clock.instant().plusSeconds(60));
        taskService.get("cache-cancel");

        taskService.cancel("cache-cancel");

        assertThat(cached("cache-cancel")).isNull();
        assertThat(taskService.get("cache-cancel").status()).isEqualTo(TaskStatus.CANCELLED);
    }

    /**
     * A read that lands after trigger() returns but before its transaction
     * commits still sees PENDING and caches it, so the eviction has to run
     * after the commit to remove it.
     */
    @Test
    void triggerEvictsAfterCommit() {
        saveTask("cache-trigger", clock.instant().minusSeconds(1));
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    // Another request reads the task while this commit is pending.
                    TaskResponse seen = CompletableFuture.supplyAsync(() -> taskService.get("cache-trigger")).join();
                    assertThat(seen.status()).isEqualTo(TaskStatus.PENDING);
                }
            });
            return null;
        }).when(publisher).publish(any());

        assertThat(triggerService.trigger("cache-trigger")).isEqualTo(TriggerOutcome.TRIGGERED);

        assertThat(cached("cache-trigger")).as("stale PENDING entry evicted").isNull();
        assertThat(taskService.get("cache-trigger").status()).isEqualTo(TaskStatus.TRIGGERED);
    }
}
