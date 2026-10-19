package com.example.demo.service;

import com.example.demo.repository.ScheduledTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit test: the loop logic only, with the repository and trigger service mocked. */
@ExtendWith(MockitoExtension.class)
class TaskPollerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    @Mock
    private ScheduledTaskRepository repository;

    @Mock
    private TaskTriggerService triggerService;

    private TaskPoller poller;

    @BeforeEach
    void setUp() {
        poller = new TaskPoller(repository, triggerService, Clock.fixed(NOW, ZoneOffset.UTC), 100);
    }

    @Test
    void triggersEveryDueTask() {
        when(repository.findDueTaskIds(NOW, Limit.of(100))).thenReturn(List.of("a", "b", "c"));
        when(triggerService.trigger(anyString())).thenReturn(TriggerOutcome.TRIGGERED);

        poller.pollDueTasks();

        verify(triggerService).trigger("a");
        verify(triggerService).trigger("b");
        verify(triggerService).trigger("c");
    }

    @Test
    void skippedTasksDoNotStopThePoll() {
        when(repository.findDueTaskIds(NOW, Limit.of(100))).thenReturn(List.of("a", "b"));
        when(triggerService.trigger("a")).thenReturn(TriggerOutcome.SKIPPED);
        when(triggerService.trigger("b")).thenReturn(TriggerOutcome.TRIGGERED);

        poller.pollDueTasks();

        verify(triggerService).trigger("b");
    }

    @Test
    void rejectedTasksDoNotStopThePoll() {
        when(repository.findDueTaskIds(NOW, Limit.of(100))).thenReturn(List.of("a", "b"));
        when(triggerService.trigger("a")).thenReturn(TriggerOutcome.REJECTED);
        when(triggerService.trigger("b")).thenReturn(TriggerOutcome.TRIGGERED);

        poller.pollDueTasks();

        verify(triggerService).trigger("b");
    }

    @Test
    void failedPublishStopsThePoll() {
        when(repository.findDueTaskIds(NOW, Limit.of(100))).thenReturn(List.of("a", "b", "c"));
        when(triggerService.trigger("a")).thenReturn(TriggerOutcome.TRIGGERED);
        when(triggerService.trigger("b")).thenReturn(TriggerOutcome.SEND_FAILED);

        poller.pollDueTasks();

        verify(triggerService, never()).trigger("c");
    }
}
