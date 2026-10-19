package com.example.demo.config;

import com.example.demo.model.dto.TaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * Caches single tasks (GET /tasks/{taskId}) in Redis. MySQL stays the source
 * of truth: entries are evicted after every status change, and the TTL bounds
 * how long a missed eviction can serve stale data.
 *
 * <p>The caching advice runs outermost, around the transaction advice, so an
 * {@code @CacheEvict} on a {@code @Transactional} method evicts after the
 * commit. Evicting before it would let a concurrent read cache the old row
 * again. (A transaction-aware cache manager also defers evictions, but its
 * after-commit eviction bypasses the error handler below.)
 */
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfig implements CachingConfigurer {

    public static final String TASKS = "tasks";

    @Bean
    public RedisCacheManagerBuilderCustomizer taskCacheCustomizer(
            ObjectMapper objectMapper, @Value("${app.cache.task-ttl}") Duration ttl) {
        // JSON written by Spring's ObjectMapper, so entries read like API responses.
        // Typed to TaskResponse, so no Java class names are stored in the JSON.
        Jackson2JsonRedisSerializer<TaskResponse> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, TaskResponse.class);
        return builder -> builder.withCacheConfiguration(TASKS, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(serializer)));
    }

    /**
     * While Redis is unreachable, fail cache calls at once instead of queueing
     * them until the command timeout; otherwise every GET would wait out the
     * timeout twice (read, then write back) before answering from MySQL.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.clientOptions(builder.build().getClientOptions()
                .map(ClientOptions::mutate)
                .orElseGet(ClientOptions::builder)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }

    /** A Redis failure degrades to a cache miss (logged as a warning) instead of failing the request. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
