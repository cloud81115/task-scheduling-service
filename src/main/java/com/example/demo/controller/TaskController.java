package com.example.demo.controller;

import com.example.demo.model.TaskStatus;
import com.example.demo.model.dto.CreateTaskRequest;
import com.example.demo.model.dto.PageResponse;
import com.example.demo.model.dto.TaskResponse;
import com.example.demo.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/*
 * The @ApiResponse annotations only document the API for Swagger: springdoc
 * infers the success response from the return type, but cannot see the error
 * responses produced by ApiExceptionHandler or the 201 set via ResponseEntity.
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "Schedule a task")
    @ApiResponse(responseCode = "201", description = "Task created",
            content = @Content(schema = @Schema(implementation = TaskResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (e.g. executeAt in the past)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A task with this taskId already exists",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        TaskResponse created = taskService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{taskId}").buildAndExpand(created.taskId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get a task")
    @ApiResponse(responseCode = "200", description = "Task found",
            content = @Content(schema = @Schema(implementation = TaskResponse.class)))
    @ApiResponse(responseCode = "404", description = "Task not found",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{taskId}")
    public TaskResponse get(@PathVariable("taskId") String taskId) {
        return taskService.get(taskId);
    }

    @Operation(summary = "List tasks by status, ordered by executeAt")
    @ApiResponse(responseCode = "200", description = "One page of tasks")
    @ApiResponse(responseCode = "400", description = "Unknown status, or page / size out of range",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public PageResponse<TaskResponse> list(
            @RequestParam(name = "status", defaultValue = "pending") TaskStatus status,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return taskService.list(status, page, size);
    }

    @Operation(summary = "Cancel a pending task")
    @ApiResponse(responseCode = "200", description = "Task cancelled",
            content = @Content(schema = @Schema(implementation = TaskResponse.class)))
    @ApiResponse(responseCode = "404", description = "Task not found",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Task is already triggered, cancelled or failed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{taskId}")
    public TaskResponse cancel(@PathVariable("taskId") String taskId) {
        return taskService.cancel(taskId);
    }
}
