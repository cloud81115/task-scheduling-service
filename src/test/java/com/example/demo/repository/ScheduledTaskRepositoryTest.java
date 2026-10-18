package com.example.demo.repository;

import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the MySQL from docker-compose (not an embedded DB), so the
 * JSON column and the real schema are exercised. Each test starts from an
 * empty table and rolls back, so leftover rows never leak into assertions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ScheduledTaskRepositoryTest {

    private static final Map<String, Object> PAYLOAD =
            Map.of("type", "email", "target", "hello@example.com", "message", "hi");

    @Autowired
    private ScheduledTaskRepository repository;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void startFromEmptyTable() {
        repository.deleteAllInBatch();
    }

    private ScheduledTask save(String taskId, Instant executeAt) {
        return repository.saveAndFlush(ScheduledTask.pending(taskId, executeAt, PAYLOAD));
    }

    @Test
    void savesAndReadsBackJsonPayload() {
        save("t-json", now.plusSeconds(60));

        ScheduledTask found = repository.findById("t-json").orElseThrow();

        assertThat(found.getPayload()).isEqualTo(PAYLOAD);
        assertThat(found.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void findByStatusPaginates() {
        save("t-1", now.plusSeconds(10));
        save("t-2", now.plusSeconds(20));
        save("t-3", now.plusSeconds(30));

        Page<ScheduledTask> page = repository.findByStatus(
                TaskStatus.PENDING, PageRequest.of(0, 2, Sort.by("executeAt")));

        assertThat(page.getContent()).extracting(ScheduledTask::getTaskId).containsExactly("t-1", "t-2");
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void findDueTaskIdsReturnsOnlyDuePendingTasksInOrder() {
        save("due-late", now.minusSeconds(10));
        save("due-early", now.minusSeconds(20));
        save("future", now.plusSeconds(60));
        save("cancelled", now.minusSeconds(30));
        repository.markCancelled("cancelled", now);

        List<String> due = repository.findDueTaskIds(now, Limit.of(10));

        assertThat(due).containsExactly("due-early", "due-late");
    }

    @Test
    void findDueTaskIdsRespectsLimit() {
        save("due-a", now.minusSeconds(3));
        save("due-b", now.minusSeconds(2));
        save("due-c", now.minusSeconds(1));

        assertThat(repository.findDueTaskIds(now, Limit.of(2))).containsExactly("due-a", "due-b");
    }

    @Test
    void markTriggeredSucceedsOnlyOnce() {
        save("t-trigger", now.minusSeconds(1));

        assertThat(repository.markTriggered("t-trigger", now)).isEqualTo(1);
        assertThat(repository.markTriggered("t-trigger", now)).isZero();

        ScheduledTask task = repository.findById("t-trigger").orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TRIGGERED);
        assertThat(task.getTriggeredAt()).isEqualTo(now);
    }

    @Test
    void cancelAndTriggerAreMutuallyExclusive() {
        save("t-cancel-first", now.minusSeconds(1));
        save("t-trigger-first", now.minusSeconds(1));

        assertThat(repository.markCancelled("t-cancel-first", now)).isEqualTo(1);
        assertThat(repository.markTriggered("t-cancel-first", now)).isZero();

        assertThat(repository.markTriggered("t-trigger-first", now)).isEqualTo(1);
        assertThat(repository.markCancelled("t-trigger-first", now)).isZero();

        assertThat(repository.findById("t-cancel-first").orElseThrow().getStatus())
                .isEqualTo(TaskStatus.CANCELLED);
        assertThat(repository.findById("t-trigger-first").orElseThrow().getStatus())
                .isEqualTo(TaskStatus.TRIGGERED);
    }

    @Test
    void revertToPendingUndoesTrigger() {
        save("t-revert", now.minusSeconds(1));
        repository.markTriggered("t-revert", now);

        assertThat(repository.revertToPending("t-revert", now)).isEqualTo(1);
        assertThat(repository.revertToPending("t-revert", now)).isZero();

        ScheduledTask task = repository.findById("t-revert").orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getTriggeredAt()).isNull();
        assertThat(repository.findDueTaskIds(now, Limit.of(10))).containsExactly("t-revert");
    }

    /** Every (current status, operation) pair: only the allowed transitions update a row. */
    @ParameterizedTest(name = "{0} + {1} -> {2} row(s), ends {3}")
    @CsvSource({
            "PENDING,   markTriggered,   1, TRIGGERED",
            "PENDING,   markCancelled,   1, CANCELLED",
            "PENDING,   revertToPending, 0, PENDING",
            "TRIGGERED, markTriggered,   0, TRIGGERED",
            "TRIGGERED, markCancelled,   0, TRIGGERED",
            "TRIGGERED, revertToPending, 1, PENDING",
            "CANCELLED, markTriggered,   0, CANCELLED",
            "CANCELLED, markCancelled,   0, CANCELLED",
            "CANCELLED, revertToPending, 0, CANCELLED",
    })
    void statusTransitionMatrix(TaskStatus from, String operation, int expectedRows, TaskStatus expectedStatus) {
        save("t-matrix", now.minusSeconds(1));
        moveTo("t-matrix", from);

        int rows = switch (operation) {
            case "markTriggered" -> repository.markTriggered("t-matrix", now);
            case "markCancelled" -> repository.markCancelled("t-matrix", now);
            case "revertToPending" -> repository.revertToPending("t-matrix", now);
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };

        assertThat(rows).isEqualTo(expectedRows);
        assertThat(repository.findById("t-matrix").orElseThrow().getStatus()).isEqualTo(expectedStatus);
    }

    /** Puts a freshly saved PENDING task into the given status. */
    private void moveTo(String taskId, TaskStatus status) {
        if (status == TaskStatus.TRIGGERED) {
            assertThat(repository.markTriggered(taskId, now)).isEqualTo(1);
        } else if (status == TaskStatus.CANCELLED) {
            assertThat(repository.markCancelled(taskId, now)).isEqualTo(1);
        }
    }
}
