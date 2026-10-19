package com.example.demo.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Measures the value with Spring's ObjectMapper, i.e. the same JSON that is
 * stored in MySQL and published to RocketMQ. Spring creates validators as
 * beans, so the ObjectMapper is injected.
 */
public class MaxJsonSizeValidator implements ConstraintValidator<MaxJsonSize, Object> {

    private final ObjectMapper objectMapper;
    private int maxBytes;

    public MaxJsonSizeValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void initialize(MaxJsonSize constraint) {
        maxBytes = constraint.value();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            return objectMapper.writeValueAsBytes(value).length <= maxBytes;
        } catch (JsonProcessingException e) {
            // Could not be published either.
            return false;
        }
    }
}
