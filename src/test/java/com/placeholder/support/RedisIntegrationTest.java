package com.placeholder.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis가 필요한 통합 테스트 베이스. {@link MySQLIntegrationTest}의 싱글톤 패턴을 그대로 이어받아
 * Redis 컨테이너도 JVM 단위로 한 번만 기동하고 stop 하지 않는다.
 *
 * <p>@ServiceConnection(name = "redis")로 Spring Boot가 spring.data.redis.* 를 자동 주입한다
 * (GenericContainer + name="redis"가 RedisContainerConnectionDetailsFactory와 매칭).
 */
public abstract class RedisIntegrationTest extends MySQLIntegrationTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        redis.start();
    }
}
