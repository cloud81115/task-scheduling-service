package com.example.demo.controller;

import com.example.demo.model.TaskStatus;
import com.example.demo.model.dto.PageResponse;
import com.example.demo.model.dto.TaskResponse;
import com.example.demo.service.TaskConflictException;
import com.example.demo.service.TaskNotFoundException;
import com.example.demo.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.example.demo.model.dto.CreateTaskRequest.MAX_PAYLOAD_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer only: request mapping, validation and error responses, with the
 * service mocked. Errors are RFC 9457 problem details.
 */
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    private static final Instant EXECUTE_AT = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService taskService;

    private ResultActions postJson(String body) throws Exception {
        return mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions postTask(String taskId, Object executeAt, Map<String, Object> payload) throws Exception {
        return postJson(objectMapper.writeValueAsString(Map.of(
                "taskId", taskId, "executeAt", executeAt.toString(), "payload", payload)));
    }

    private static TaskResponse pending(String taskId) {
        return new TaskResponse(taskId, TaskStatus.PENDING, EXECUTE_AT, Map.of("type", "email"),
                Instant.now(), Instant.now(), null);
    }

    /** A payload that serializes to exactly {@code bytes} bytes of JSON. */
    private Map<String, Object> payloadOfSize(int bytes) throws Exception {
        Map<String, Object> payload = Map.of("data", "x".repeat(bytes - "{\"data\":\"\"}".length()));
        assertThat(objectMapper.writeValueAsBytes(payload)).hasSize(bytes);
        return payload;
    }

    // POST /tasks

    @Test
    void createReturnsTheTaskWithItsLocation() throws Exception {
        when(taskService.create(any())).thenReturn(pending("abc-123"));

        postTask("abc-123", EXECUTE_AT, Map.of("type", "email"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/tasks/abc-123"))
                .andExpect(jsonPath("$.taskId").value("abc-123"))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.executeAt").value(EXECUTE_AT.toString()));
    }

    @Test
    void invalidFieldsAreListedInErrors() throws Exception {
        postTask(" ", Instant.now().minusSeconds(60), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                // Blank also fails the pattern; only one message per field is kept.
                .andExpect(jsonPath("$.errors.taskId").exists())
                .andExpect(jsonPath("$.errors.executeAt").value("must be a future date"))
                .andExpect(jsonPath("$.errors.payload").value("must not be empty"));

        verifyNoInteractions(taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "order:42", "v1.2", "a_b", "3f2b8c1e-9d4a-4f6b-8e2a-1c5d7f9b0a3e"})
    void taskIdsSafeInUrlsAndMessageKeysAreAccepted(String taskId) throws Exception {
        when(taskService.create(any())).thenReturn(pending(taskId));

        postTask(taskId, EXECUTE_AT, Map.of("type", "email")).andExpect(status().isCreated());
    }

    /** These could be created but never fetched or cancelled by URL, or would break the message key. */
    @ParameterizedTest
    @ValueSource(strings = {"a/b", " abc ", "order 123", ".", "..", "-x", "a?b", "a#b", "a%2Fb"})
    void taskIdsUnsafeInUrlsOrMessageKeysAreRejected(String taskId) throws Exception {
        postTask(taskId, EXECUTE_AT, Map.of("type", "email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.taskId").value(
                        "must start with a letter or digit and contain only letters, digits, '.', '_', ':' or '-'"));

        verifyNoInteractions(taskService);
    }

    @Test
    void payloadAtTheLimitIsAccepted() throws Exception {
        when(taskService.create(any())).thenReturn(pending("big"));

        postTask("big", EXECUTE_AT, payloadOfSize(MAX_PAYLOAD_BYTES)).andExpect(status().isCreated());
    }

    @Test
    void payloadOverTheLimitIsRejected() throws Exception {
        postTask("too-big", EXECUTE_AT, payloadOfSize(MAX_PAYLOAD_BYTES + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.payload").value("must be at most 65536 bytes as JSON"));

        verifyNoInteractions(taskService);
    }

    /** Without a time zone the instant is ambiguous, so it is rejected rather than guessed. */
    @Test
    void executeAtWithoutTimeZoneIsRejected() throws Exception {
        postTask("no-zone", "2099-01-01T10:00:00", Map.of("type", "email"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON));

        verifyNoInteractions(taskService);
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        postJson("{\"taskId\": ")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON));
    }

    @Test
    void duplicateTaskIdIsConflict() throws Exception {
        when(taskService.create(any())).thenThrow(new TaskConflictException("Task abc-123 already exists"));

        postTask("abc-123", EXECUTE_AT, Map.of("type", "email"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Task abc-123 already exists"));
    }

    // GET /tasks/{taskId}

    @Test
    void getReturnsTheTask() throws Exception {
        when(taskService.get("abc-123")).thenReturn(pending("abc-123"));

        mockMvc.perform(get("/tasks/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("abc-123"))
                .andExpect(jsonPath("$.payload.type").value("email"));
    }

    @Test
    void unknownTaskIsNotFound() throws Exception {
        when(taskService.get("nope")).thenThrow(new TaskNotFoundException("nope"));

        mockMvc.perform(get("/tasks/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Task nope not found"));
    }

    // GET /tasks

    @Test
    void listDefaultsToPendingFirstPage() throws Exception {
        when(taskService.list(TaskStatus.PENDING, 0, 20))
                .thenReturn(new PageResponse<>(List.of(pending("abc-123")), 0, 20, 1, 1));

        mockMvc.perform(get("/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].taskId").value("abc-123"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** The API uses lowercase statuses, but any case is accepted. */
    @Test
    void listAcceptsStatusInAnyCase() throws Exception {
        mockMvc.perform(get("/tasks").param("status", "failed").param("page", "2").param("size", "5"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/tasks").param("status", "TRIGGERED")).andExpect(status().isOk());

        verify(taskService).list(TaskStatus.FAILED, 2, 5);
        verify(taskService).list(TaskStatus.TRIGGERED, 0, 20);
    }

    @Test
    void unknownStatusIsRejected() throws Exception {
        mockMvc.perform(get("/tasks").param("status", "done"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON));

        verifyNoInteractions(taskService);
    }

    @Test
    void pageAndSizeOutOfRangeAreListedInErrors() throws Exception {
        mockMvc.perform(get("/tasks").param("page", "-1").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.page").value("must be greater than or equal to 0"))
                .andExpect(jsonPath("$.errors.size").value("must be less than or equal to 100"));

        verifyNoInteractions(taskService);
    }

    // DELETE /tasks/{taskId}

    @Test
    void cancelReturnsTheCancelledTask() throws Exception {
        TaskResponse cancelled = new TaskResponse("abc-123", TaskStatus.CANCELLED, EXECUTE_AT, Map.of("type", "email"),
                Instant.now(), Instant.now(), null);
        when(taskService.cancel("abc-123")).thenReturn(cancelled);

        mockMvc.perform(delete("/tasks/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void cancellingAFinishedTaskIsConflict() throws Exception {
        when(taskService.cancel("abc-123"))
                .thenThrow(new TaskConflictException("Task abc-123 is already triggered and cannot be cancelled"));

        mockMvc.perform(delete("/tasks/abc-123"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Task abc-123 is already triggered and cannot be cancelled"));
    }
}
