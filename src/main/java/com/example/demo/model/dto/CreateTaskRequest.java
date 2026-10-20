package com.example.demo.model.dto;

import com.example.demo.validation.MaxJsonSize;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public record CreateTaskRequest(
        @NotBlank @Size(max = 64)
        @Pattern(regexp = TASK_ID_PATTERN, message = "must start with a letter or digit and contain only letters, digits, '.', '_', ':' or '-'")
        String taskId,
        @NotNull @Future Instant executeAt,
        @NotEmpty @MaxJsonSize(MAX_PAYLOAD_BYTES) Map<String, Object> payload
) {
    /**
     * Safe as a URL path segment and as a RocketMQ message key. A "/" would make
     * /tasks/{taskId} unreachable (Tomcat also rejects an encoded "%2F"), "." and
     * ".." are rewritten by URL normalization, and RocketMQ splits keys on spaces.
     */
    public static final String TASK_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:-]*$";

    /**
     * Far below RocketMQ's 4 MB message limit. A larger message would be
     * rejected on every attempt and hold up the tasks due after it.
     */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
}
