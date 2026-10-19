package com.example.demo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated value, serialized as JSON (UTF-8), must be at most
 * {@link #value()} bytes. Null is valid; combine with {@code @NotNull} or
 * {@code @NotEmpty} to reject it.
 */
@Documented
@Constraint(validatedBy = MaxJsonSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxJsonSize {

    /** Maximum size in bytes. */
    int value();

    String message() default "must be at most {value} bytes as JSON";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
