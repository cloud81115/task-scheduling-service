package com.example.demo.service;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("Task " + taskId + " not found");
    }
}
