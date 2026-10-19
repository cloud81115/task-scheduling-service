package com.example.demo.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses the ObjectMapper Spring Boot actually configures (@JsonTest): a plain
 * Jackson2ObjectMapperBuilder writes Instant as a number, because Boot itself
 * is what turns off WRITE_DATES_AS_TIMESTAMPS.
 */
@JsonTest
@ExtendWith(MockitoExtension.class)
class TaskTriggerPublisherTest {

    private static final String TOPIC = "task-schedule-topic";
    private static final TaskTriggerMessage MESSAGE = new TaskTriggerMessage("abc-123",
            Instant.parse("2026-10-18T10:00:00.123Z"), Instant.parse("2026-10-18T10:00:00.456Z"),
            Map.of("type", "email"));

    @Autowired
    private ObjectMapper objectMapper;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    /** Not started: only its settings (the size limit) are read. */
    private final DefaultMQProducer producer = new DefaultMQProducer();

    private TaskTriggerPublisher publisher;

    @BeforeEach
    void setUp() {
        when(rocketMQTemplate.getProducer()).thenReturn(producer);
        publisher = new TaskTriggerPublisher(rocketMQTemplate, objectMapper, TOPIC);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsJsonBodyKeyedByTaskId() throws Exception {
        when(rocketMQTemplate.syncSend(eq(TOPIC), any(Message.class))).thenReturn(result(SendStatus.SEND_OK));

        publisher.publish(MESSAGE);

        ArgumentCaptor<Message<String>> sent = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq(TOPIC), sent.capture());
        assertThat(sent.getValue().getHeaders().get(RocketMQHeaders.KEYS)).isEqualTo("abc-123");

        JsonNode body = objectMapper.readTree(sent.getValue().getPayload());
        assertThat(body.get("taskId").asText()).isEqualTo("abc-123");
        assertThat(body.get("executeAt").asText()).isEqualTo("2026-10-18T10:00:00.123Z");
        assertThat(body.get("triggeredAt").asText()).isEqualTo("2026-10-18T10:00:00.456Z");
        assertThat(body.get("payload").get("type").asText()).isEqualTo("email");
    }

    @Test
    void nonOkSendStatusIsTreatedAsFailure() {
        when(rocketMQTemplate.syncSend(eq(TOPIC), any(Message.class)))
                .thenReturn(result(SendStatus.FLUSH_DISK_TIMEOUT));

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isExactlyInstanceOf(MessagingException.class)
                .hasMessageContaining("FLUSH_DISK_TIMEOUT");
    }

    @Test
    void messageOverTheSizeLimitIsUnpublishableAndNotSent() {
        producer.setMaxMessageSize(100);

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOf(UnpublishableMessageException.class)
                .hasMessageContaining("over the limit of 100");
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
    }

    @Test
    void messageRejectedByTheBrokerIsUnpublishable() {
        when(rocketMQTemplate.syncSend(eq(TOPIC), any(Message.class))).thenThrow(new MessagingException("send failed",
                new MQBrokerException(ResponseCode.MESSAGE_ILLEGAL, "message body size exceeded")));

        assertThatThrownBy(() -> publisher.publish(MESSAGE)).isInstanceOf(UnpublishableMessageException.class);
    }

    /** The client uses the same code for an invalid topic name, which affects every task, not just this one. */
    @Test
    void clientSideMessageIllegalStaysRetryable() {
        when(rocketMQTemplate.syncSend(eq(TOPIC), any(Message.class))).thenThrow(new MessagingException("send failed",
                new MQClientException(ResponseCode.MESSAGE_ILLEGAL, "topic contains illegal characters")));

        assertThatThrownBy(() -> publisher.publish(MESSAGE)).isExactlyInstanceOf(MessagingException.class);
    }

    @Test
    void otherBrokerErrorsStayRetryable() {
        when(rocketMQTemplate.syncSend(eq(TOPIC), any(Message.class))).thenThrow(new MessagingException("send failed",
                new MQBrokerException(ResponseCode.SYSTEM_BUSY, "broker busy")));

        assertThatThrownBy(() -> publisher.publish(MESSAGE)).isExactlyInstanceOf(MessagingException.class);
    }

    private static SendResult result(SendStatus status) {
        SendResult result = new SendResult();
        result.setSendStatus(status);
        return result;
    }
}
