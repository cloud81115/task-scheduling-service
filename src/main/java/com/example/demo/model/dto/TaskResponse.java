package com.example.demo.model.dto;

import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;

import java.time.Instant;
import java.util.Map;

public record TaskResponse(
        String taskId,
        TaskStatus status,
        Instant executeAt,
        Map<String, Object> payload,
        Instant createdAt,
        Instant updatedAt,
        Instant triggeredAt
) {
    public static TaskResponse from(ScheduledTask task) {
        return new TaskResponse(
                task.getTaskId(),
                task.getStatus(),
                task.getExecuteAt(),
                task.getPayload(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getTriggeredAt());
    }
}
