package com.example.demo.service;

/** Result of trying to trigger one due task. */
public enum TriggerOutcome {

    /** Published to MQ and marked TRIGGERED. */
    TRIGGERED,

    /** Not PENDING any more (cancelled, triggered, failed), unknown, or locked by another poller. */
    SKIPPED,

    /** Publishing failed; the task stays PENDING and is retried on the next poll. */
    SEND_FAILED,

    /** The message can never be published; the task is marked FAILED and the poll goes on. */
    REJECTED
}
