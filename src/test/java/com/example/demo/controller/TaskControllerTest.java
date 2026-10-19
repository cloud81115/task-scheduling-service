package com.example.demo.controller;

import com.example.demo.model.TaskStatus;
import com.example.demo.model.dto.TaskResponse;
import com.example.demo.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static com.example.demo.model.dto.CreateTaskRequest.MAX_PAYLOAD_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only: request mapping, validation and error responses, with the service mocked. */
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    private static final Instant EXECUTE_AT = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService taskService;

    private ResultActions postTask(String taskId, Map<String, Object> payload) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "taskId", taskId, "executeAt", EXECUTE_AT.toString(), "payload", payload));
        return mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** A payload that serializes to exactly {@code bytes} bytes of JSON. */
    private Map<String, Object> payloadOfSize(int bytes) throws Exception {
        Map<String, Object> payload = Map.of("data", "x".repeat(bytes - "{\"data\":\"\"}".length()));
        assertThat(objectMapper.writeValueAsBytes(payload)).hasSize(bytes);
        return payload;
    }

    @Test
    void payloadAtTheLimitIsAccepted() throws Exception {
        when(taskService.create(any())).thenReturn(new TaskResponse(
                "big", TaskStatus.PENDING, EXECUTE_AT, Map.of(), Instant.now(), Instant.now(), null));

        postTask("big", payloadOfSize(MAX_PAYLOAD_BYTES))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/tasks/big"));
    }

    @Test
    void payloadOverTheLimitIsRejected() throws Exception {
        postTask("too-big", payloadOfSize(MAX_PAYLOAD_BYTES + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.payload").value("must be at most 65536 bytes as JSON"));

        verifyNoInteractions(taskService);
    }
}
