package com.example.demo.service;

import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import com.example.demo.model.dto.CreateTaskRequest;
import com.example.demo.model.dto.PageResponse;
import com.example.demo.model.dto.TaskResponse;
import com.example.demo.repository.ScheduledTaskRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class TaskService {

    private static final Sort BY_EXECUTE_AT = Sort.by("executeAt").and(Sort.by("id"));

    private final ScheduledTaskRepository repository;
    private final Clock clock;

    public TaskService(ScheduledTaskRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public TaskResponse create(CreateTaskRequest request) {
        ScheduledTask task = new ScheduledTask(
                request.taskId(), request.executeAt(), request.payload(), clock.instant());
        try {
            return TaskResponse.from(repository.saveAndFlush(task));
        } catch (DataIntegrityViolationException e) {
            // The uk_task_id constraint rejects a duplicate id, even when two requests race.
            throw new TaskConflictException("Task " + request.taskId() + " already exists");
        }
    }

    public TaskResponse get(String taskId) {
        return repository.findByTaskId(taskId)
                .map(TaskResponse::from)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    public PageResponse<TaskResponse> list(TaskStatus status, int page, int size) {
        return PageResponse.from(repository
                .findByStatus(status, PageRequest.of(page, size, BY_EXECUTE_AT))
                .map(TaskResponse::from));
    }

    public TaskResponse cancel(String taskId) {
        // Update first and let the database decide; reading the status first and
        // then cancelling would race with the poller triggering the same task.
        int updated = repository.markCancelled(taskId, clock.instant());
        ScheduledTask task = repository.findByTaskId(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (updated == 0) {
            throw new TaskConflictException(
                    "Task " + taskId + " is already " + task.getStatus().toValue() + " and cannot be cancelled");
        }
        return TaskResponse.from(task);
    }
}
