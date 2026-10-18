package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "scheduled_task")
@Getter
@Setter
public class ScheduledTask {

    @Id
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "execute_at", nullable = false)
    private Instant executeAt;

    /** Free-form JSON; Hibernate serializes the map with Jackson. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TaskStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "triggered_at")
    private Instant triggeredAt;

    /**
     * Required by JPA; hidden so callers use {@link #pending}.
     * Written by hand instead of Lombok's @NoArgsConstructor, which the
     * Eclipse compiler in the editor's Java extension emits with an empty body.
     */
    protected ScheduledTask() {
    }

    public static ScheduledTask pending(String taskId, Instant executeAt, Map<String, Object> payload) {
        ScheduledTask task = new ScheduledTask();
        task.taskId = taskId;
        task.executeAt = executeAt;
        task.payload = payload;
        task.status = TaskStatus.PENDING;
        return task;
    }
}
