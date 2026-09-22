package com.subreathingcode.URLShortener.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Default Redis cache values are JDK-serialized, which would require every
 * cached entity to implement Serializable. Using JSON instead avoids that
 * coupling and keeps cached values human-readable in Redis.
 * <p>
 * Default typing is enabled so the deserializer knows to produce a
 * {@code ShortUrl} instead of a generic LinkedHashMap on cache reads.
 */
@Configuration
public class RedisCacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
                .enableUnsafeDefaultTyping()
                .build();

        return builder -> builder.cacheDefaults(
                RedisCacheConfiguration.defaultCacheConfig()
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
    }
}
