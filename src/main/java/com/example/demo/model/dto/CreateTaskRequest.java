package com.example.demo.model.dto;

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
        @NotEmpty Map<String, Object> payload
) {
}
