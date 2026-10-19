package com.example.demo.service;

import com.example.demo.repository.ScheduledTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Polls MySQL for due PENDING tasks and hands each to {@link TaskTriggerService}.
 * Disabled with {@code app.scheduler.enabled=false} (tests do this, so the
 * poller does not race with them).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TaskPoller {

    private static final Logger log = LoggerFactory.getLogger(TaskPoller.class);

    private final ScheduledTaskRepository repository;
    private final TaskTriggerService triggerService;
    private final Clock clock;
    private final int batchSize;

    public TaskPoller(ScheduledTaskRepository repository, TaskTriggerService triggerService, Clock clock,
                      @Value("${app.scheduler.batch-size}") int batchSize) {
        this.repository = repository;
        this.triggerService = triggerService;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** fixedDelay: the next poll starts only after this one finishes, so polls never overlap. */
    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval-ms}",
            initialDelayString = "${app.scheduler.initial-delay-ms:0}")
    public void pollDueTasks() {
        List<String> dueTaskIds = repository.findDueTaskIds(clock.instant(), Limit.of(batchSize));
        for (int i = 0; i < dueTaskIds.size(); i++) {
            TriggerOutcome outcome = triggerService.trigger(dueTaskIds.get(i));
            if (outcome == TriggerOutcome.SEND_FAILED) {
                // The broker is likely down; trying the rest would only wait out
                // one send timeout per task. Retry everything on the next poll.
                log.warn("Stopping this poll after a failed publish; {} due task(s) left for the next one",
                        dueTaskIds.size() - i);
                return;
            }
        }
    }
}
