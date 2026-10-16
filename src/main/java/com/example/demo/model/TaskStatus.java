package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum TaskStatus {
    PENDING,
    TRIGGERED,
    CANCELLED;

    /** Exposed in JSON and query params as lowercase, e.g. "pending". */
    @JsonValue
    public String toValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TaskStatus fromValue(String value) {
        for (TaskStatus status : values()) {
            if (status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status: " + value);
    }
}
