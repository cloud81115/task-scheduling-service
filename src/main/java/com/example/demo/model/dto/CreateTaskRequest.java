package com.example.demo.model.dto;

import com.example.demo.validation.MaxJsonSize;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public record CreateTaskRequest(
        @NotBlank @Size(max = 64) String taskId,
        @NotNull @Future Instant executeAt,
        @NotEmpty @MaxJsonSize(MAX_PAYLOAD_BYTES) Map<String, Object> payload
) {
    /**
     * Far below RocketMQ's 4 MB message limit. A larger message would be
     * rejected on every attempt and hold up the tasks due after it.
     */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
}
