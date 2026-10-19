package com.example.demo.service;

import com.example.demo.model.ScheduledTask;
import com.example.demo.mq.TaskTriggerMessage;
import com.example.demo.mq.TaskTriggerPublisher;
import com.example.demo.repository.ScheduledTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Triggers one due task: lock it, publish it, then mark it TRIGGERED, all in
 * one transaction.
 *
 * <p>Publishing happens before marking, inside the transaction holding the row
 * lock. If the app dies before commit, the transaction rolls back and the task
 * is still PENDING, so the next poll publishes it again: delivery is
 * at-least-once, never lost. The price is that a message can be published
 * twice (e.g. a crash between publish and commit), so consumers deduplicate by
 * taskId. The row stays locked while publishing, which is bounded by the
 * RocketMQ send timeout; a concurrent cancel waits for it.
 *
 * <p>Kept separate from {@link TaskPoller}: {@code @Transactional} only
 * applies to calls coming through the Spring proxy, so a self-invoked method
 * in the same class would run without a transaction.
 */
@Service
public class TaskTriggerService {

    private static final Logger log = LoggerFactory.getLogger(TaskTriggerService.class);

    private final ScheduledTaskRepository repository;
    private final TaskTriggerPublisher publisher;
    private final Clock clock;

    public TaskTriggerService(ScheduledTaskRepository repository, TaskTriggerPublisher publisher, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public TriggerOutcome trigger(String taskId) {
        ScheduledTask task = repository.lockPendingByTaskId(taskId).orElse(null);
        if (task == null) {
            // Cancelled, already triggered, unknown, or being handled by another poller.
            return TriggerOutcome.SKIPPED;
        }

        Instant now = clock.instant();
        try {
            publisher.publish(new TaskTriggerMessage(task.getTaskId(), task.getExecuteAt(), now, task.getPayload()));
        } catch (RuntimeException e) {
            // Nothing was written, so committing just releases the lock and the task stays PENDING.
            log.warn("Failed to publish task {}, will retry on the next poll: {}", taskId, e.toString());
            return TriggerOutcome.SEND_FAILED;
        }

        // We hold the row lock on a PENDING row, so this always updates exactly one row.
        repository.markTriggered(taskId, now);
        return TriggerOutcome.TRIGGERED;
    }
}
