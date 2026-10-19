package com.example.demo.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Publishes trigger messages to RocketMQ. Sends synchronously so the caller
 * knows whether the broker accepted the message before marking the task.
 */
@Component
public class TaskTriggerPublisher {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public TaskTriggerPublisher(RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper,
                                @Value("${app.scheduler.topic}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /**
     * @throws MessagingException if the broker did not accept the message
     */
    public void publish(TaskTriggerMessage message) {
        Message<String> mqMessage = MessageBuilder
                // Serialize with Spring's ObjectMapper so times are ISO-8601 strings,
                // the same format as the REST API.
                .withPayload(toJson(message))
                // The key lets the RocketMQ console look a message up by taskId, and
                // lets consumers deduplicate redeliveries (delivery is at-least-once).
                .setHeader(RocketMQHeaders.KEYS, message.taskId())
                .build();

        SendResult result = rocketMQTemplate.syncSend(topic, mqMessage);
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            throw new MessagingException(mqMessage, "Broker returned " + result.getSendStatus());
        }
    }

    private String toJson(TaskTriggerMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize trigger message for task " + message.taskId(), e);
        }
    }
}
