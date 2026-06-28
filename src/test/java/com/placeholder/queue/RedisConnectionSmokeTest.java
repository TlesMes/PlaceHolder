package com.placeholder.queue;

import com.placeholder.support.RedisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase E-1 1단계 검증: Redis 의존성/오토컨피그/연결이 정상 동작하는지 확인하는 스모크 테스트.
 *
 * <p>대기열 로직 이전에 "Redis가 떠 있고 Spring이 붙는다"만 증명한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisConnectionSmokeTest extends RedisIntegrationTest {

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void redis_연결_set_get_왕복() {
        redisTemplate.opsForValue().set("smoke:key", "pong");

        String value = redisTemplate.opsForValue().get("smoke:key");

        assertThat(value).isEqualTo("pong");
    }
}
