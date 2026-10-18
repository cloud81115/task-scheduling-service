package com.example.demo.config;

import com.example.demo.model.TaskStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    /**
     * Injected instead of calling Instant.now() directly, so tests can fix the time.
     * Ticks in milliseconds to match the DATETIME(3) columns.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }

    /** Accepts ?status=pending (lowercase, as in the API) instead of only PENDING. */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, TaskStatus.class, TaskStatus::fromValue);
    }
}
