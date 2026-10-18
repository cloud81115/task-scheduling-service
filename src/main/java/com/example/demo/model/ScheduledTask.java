package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Entity
@Table(name = "scheduled_task")
@Getter
@Setter
public class ScheduledTask {

    /** Internal surrogate key; the API only ever exposes taskId. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Client-supplied id; uniqueness is enforced by the uk_task_id constraint. */
    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "execute_at", nullable = false)
    private Instant executeAt;

    /** Free-form JSON; Hibernate serializes the map with Jackson. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    /** Every new task starts as PENDING (init.sql has the same column default). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "triggered_at")
    private Instant triggeredAt;

    /**
     * Required by JPA; hidden so callers cannot create a task without its fields.
     * Written by hand instead of Lombok's @NoArgsConstructor, which the
     * Eclipse compiler in the editor's Java extension emits with an empty body.
     */
    protected ScheduledTask() {
    }

    /**
     * Times are truncated to milliseconds, the precision of the DATETIME(3)
     * columns, so what the API returns right after creation matches what a
     * later read from the database returns.
     */
    public ScheduledTask(String taskId, Instant executeAt, Map<String, Object> payload, Instant now) {
        Instant createdAt = now.truncatedTo(ChronoUnit.MILLIS);
        this.taskId = taskId;
        this.executeAt = executeAt.truncatedTo(ChronoUnit.MILLIS);
        this.payload = payload;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }
}
