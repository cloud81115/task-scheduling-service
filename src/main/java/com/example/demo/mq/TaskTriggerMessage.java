package com.example.demo.mq;

import java.time.Instant;
import java.util.Map;

/** Message body published to task-schedule-topic when a task becomes due. */
public record TaskTriggerMessage(
        String taskId,
        Instant executeAt,
        Instant triggeredAt,
        Map<String, Object> payload
) {
}
