package com.placeholder.queue;

import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase E-1 1단계 검증: Redis 의존성/오토컨피그/연결이 정상 동작하는지 확인하는 스모크 테스트.
 *
 * <p>대기열 로직 이전에 "Redis가 떠 있고 Spring이 붙는다"만 증명한다. MySQLIntegrationTest를
 * 상속해 JPA가 요구하는 데이터소스를 확보하고, Redis는 GenericContainer + @ServiceConnection으로
 * 별도 기동한다(Spring Boot가 "redis" 이미지를 인식해 spring.data.redis.* 를 자동 주입).
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisConnectionSmokeTest extends MySQLIntegrationTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        redis.start();
    }

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void redis_연결_set_get_왕복() {
        redisTemplate.opsForValue().set("smoke:key", "pong");

        String value = redisTemplate.opsForValue().get("smoke:key");

        assertThat(value).isEqualTo("pong");
    }
}
