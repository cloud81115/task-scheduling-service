package com.example.demo.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Publishes trigger messages to RocketMQ. Sends synchronously so the caller
 * knows whether the broker accepted the message before marking the task.
 *
 * <p>Failures are transient unless known to be caused by this message alone:
 * misjudging a transient failure (e.g. the broker restarting) as permanent
 * would mark healthy tasks FAILED, while the reverse only delays the poll.
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
     * @throws UnpublishableMessageException if this message can never be published
     * @throws MessagingException            if the broker did not accept the message (retry later)
     */
    public void publish(TaskTriggerMessage message) {
        // Serialized with Spring's ObjectMapper so times are ISO-8601 strings,
        // the same format as the REST API.
        String body = toJson(message);
        int size = body.getBytes(StandardCharsets.UTF_8).length;
        int maxSize = rocketMQTemplate.getProducer().getMaxMessageSize();
        if (size > maxSize) {
            // The client would reject it before sending, with the same error code
            // as an invalid topic name, so check here rather than classify that error.
            throw new UnpublishableMessageException(
                    "Message for task " + message.taskId() + " is " + size + " bytes, over the limit of " + maxSize);
        }

        Message<String> mqMessage = MessageBuilder
                .withPayload(body)
                // The key lets the RocketMQ console look a message up by taskId, and
                // lets consumers deduplicate redeliveries (delivery is at-least-once).
                .setHeader(RocketMQHeaders.KEYS, message.taskId())
                .build();

        SendResult result;
        try {
            result = rocketMQTemplate.syncSend(topic, mqMessage);
        } catch (MessagingException e) {
            if (rejectedByBroker(e)) {
                // E.g. the broker's size limit is lower than the client's.
                throw new UnpublishableMessageException("Broker rejected the message for task " + message.taskId(), e);
            }
            throw e;
        }
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            throw new MessagingException(mqMessage, "Broker returned " + result.getSendStatus());
        }
    }

    private String toJson(TaskTriggerMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new UnpublishableMessageException("Cannot serialize trigger message for task " + message.taskId(), e);
        }
    }

    /**
     * The broker answers MESSAGE_ILLEGAL for problems with the message itself,
     * such as its size or properties; topic names are checked by the client first.
     */
    private static boolean rejectedByBroker(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof MQBrokerException broker && broker.getResponseCode() == ResponseCode.MESSAGE_ILLEGAL) {
                return true;
            }
        }
        return false;
    }
}
