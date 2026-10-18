package com.example.demo.service;

/** The request is valid but clashes with the task's current state (duplicate id, not cancellable). */
public class TaskConflictException extends RuntimeException {

    public TaskConflictException(String message) {
        super(message);
    }
}
