package com.placeholder.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@EnableCaching
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCache eventExistsCache = new CaffeineCache(
                "eventExists",
                Caffeine.newBuilder()
                        .maximumSize(1_000)
                        .expireAfterWrite(Duration.ofSeconds(60))
                        .build()
        );
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(eventExistsCache));
        return manager;
    }
}
