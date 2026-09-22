package com.smartdelivery.product.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartdelivery.product.dto.ProductResponse;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Per ADR 005: Redis caches product reads only, is never the system of record, and a
 * cold/flushed cache must not change correctness -- only latency. Ten minutes is a
 * deliberately short default TTL so a bug in cache invalidation (see ProductService)
 * self-heals quickly rather than serving stale prices indefinitely.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRODUCTS_CACHE = "products";

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        RedisCacheConfiguration productsCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(cacheObjectMapper(), ProductResponse.class)));

        return builder -> builder.withCacheConfiguration(PRODUCTS_CACHE, productsCacheConfig);
    }

    /**
     * Its own mapper rather than the application's, because a cache entry is a stored
     * format with its own compatibility concerns, not an API response -- changing how
     * this service renders JSON to clients should not silently invalidate or, worse,
     * misread everything already in Redis.
     *
     * {@link JavaTimeModule} is the part that has to be here: {@code ProductResponse}
     * carries {@code Instant} timestamps, and a mapper without it refuses to serialize
     * them at all. That turned every cache write into a 500 -- found in Phase 16 when
     * this test suite ran end to end for the first time in a while, and fixed here even
     * though it has nothing to do with that phase, because it made a green build
     * impossible. ISO-8601 rather than epoch numbers so a cached entry stays readable
     * with {@code redis-cli} when something needs diagnosing.
     */
    private static ObjectMapper cacheObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
