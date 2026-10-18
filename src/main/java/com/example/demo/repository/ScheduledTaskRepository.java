package com.example.demo.repository;

import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {

    Page<ScheduledTask> findByStatus(TaskStatus status, Pageable pageable);

    @Query("""
            select t.taskId from ScheduledTask t
             where t.status = com.example.demo.model.TaskStatus.PENDING
               and t.executeAt <= :now
             order by t.executeAt
            """)
    List<String> findDueTaskIds(@Param("now") Instant now, Limit limit);

    /*
     * Status transitions are conditional updates: the WHERE clause checks the
     * current status, so when two callers race (two pollers, or a poller and a
     * cancel request) only one of them gets 1 row back. Bulk updates bypass
     * @UpdateTimestamp, so updatedAt is set by hand here.
     */

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScheduledTask t
               set t.status = com.example.demo.model.TaskStatus.TRIGGERED,
                   t.triggeredAt = :now, t.updatedAt = :now
             where t.taskId = :taskId
               and t.status = com.example.demo.model.TaskStatus.PENDING
            """)
    int markTriggered(@Param("taskId") String taskId, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScheduledTask t
               set t.status = com.example.demo.model.TaskStatus.CANCELLED,
                   t.updatedAt = :now
             where t.taskId = :taskId
               and t.status = com.example.demo.model.TaskStatus.PENDING
            """)
    int markCancelled(@Param("taskId") String taskId, @Param("now") Instant now);

    /** Undo markTriggered when publishing to MQ fails, so the next poll retries it. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScheduledTask t
               set t.status = com.example.demo.model.TaskStatus.PENDING,
                   t.triggeredAt = null, t.updatedAt = :now
             where t.taskId = :taskId
               and t.status = com.example.demo.model.TaskStatus.TRIGGERED
            """)
    int revertToPending(@Param("taskId") String taskId, @Param("now") Instant now);
}
