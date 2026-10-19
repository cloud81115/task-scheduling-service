package com.example.demo.mq;

/**
 * The message itself can never be published, so retrying is pointless (unlike
 * a broker that is down, which fails every message until it recovers).
 */
public class UnpublishableMessageException extends RuntimeException {

    public UnpublishableMessageException(String message) {
        super(message);
    }

    public UnpublishableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
