package com.example.demo.service;

/** Result of trying to trigger one due task. */
public enum TriggerOutcome {

    /** Published to MQ and marked TRIGGERED. */
    TRIGGERED,

    /** Not PENDING any more (cancelled, already triggered, unknown) or locked by another poller. */
    SKIPPED,

    /** Publishing failed; the task stays PENDING and is retried on the next poll. */
    SEND_FAILED
}
