package com.placeholder.support;

import com.placeholder.domain.queue.service.QueueAdmissionService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis가 필요한 통합 테스트 베이스. {@link MySQLIntegrationTest}의 싱글톤 패턴을 그대로 이어받아
 * Redis 컨테이너도 JVM 단위로 한 번만 기동하고 stop 하지 않는다.
 *
 * <p>@ServiceConnection(name = "redis")로 Spring Boot가 spring.data.redis.* 를 자동 주입한다
 * (GenericContainer + name="redis"가 RedisContainerConnectionDetailsFactory와 매칭).
 *
 * <p><b>공유 상태 정리를 여기서 일괄 수행한다.</b> Redis 컨테이너와 스프링 컨텍스트가 모든 하위
 * 테스트 클래스에 공유되므로 한 클래스가 남긴 상태는 다음 클래스의 결과를 바꾼다. 정리를 클래스마다
 * 복사하던 방식은 새 테스트를 추가할 때 빠뜨리기 쉬웠고 실제로 사고가 났다 —
 * {@code SeatHoldQueueGateTest}/{@code SeatQueryQueueGateTest}가 {@code entry:*}만 지우고
 * {@code active:all}(ZSET, TTL 없음)을 남겨, 유효 활성 세션 1개가 다음 클래스의 전역 ceiling을
 * 잠식해 입장 인원이 1 모자랐다. 그래서 정리를 베이스로 올려 구조적으로 차단한다.
 */
public abstract class RedisIntegrationTest extends MySQLIntegrationTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        redis.start();
    }

    @Autowired
    private StringRedisTemplate cleanupRedisTemplate;

    @Autowired
    private QueueAdmissionService cleanupAdmissionService;

    /**
     * 테스트 간 공유 상태 초기화. JUnit 5는 @AfterEach를 하위 클래스 → 상위 클래스 순으로 실행하므로
     * 이 정리는 하위 클래스의 개별 뒷정리가 끝난 뒤 마지막에 돈다.
     *
     * <p>지우는 대상이 둘이다:
     * <ol>
     *   <li><b>Redis 전체(FLUSHDB)</b> — 키 종류를 열거하지 않는다. {@code queue:*}/{@code entry:*}/
     *       {@code rate:*}/{@code active:all}처럼 목록을 유지보수하는 방식은 새 키가 생기면 또
     *       빠뜨린다. 테스트 전용 컨테이너라 통째로 비우는 편이 안전하고 규칙도 단순하다.</li>
     *   <li><b>인메모리 deficit 장부(ADR-017)</b> — {@link QueueAdmissionService}의 이월 장부는
     *       Redis가 아니라 빈 내부 상태라 FLUSHDB로 지워지지 않고, 캐시된 컨텍스트를 통해 클래스
     *       간 공유된다. 대기열이 빈 상태에서 {@code admitWaiting()}을 한 번 호출하면 진입부의
     *       {@code deficits.keySet().retainAll(활성 이벤트)}가 빈 집합과 만나 장부를 전부 비운다
     *       (후보가 없어 입장은 0건). 프로덕션에 테스트 전용 훅을 만들지 않으려는 선택이며,
     *       그 retainAll 정리에 의존한다.</li>
     * </ol>
     */
    @AfterEach
    void flushSharedQueueState() {
        cleanupRedisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        cleanupAdmissionService.admitWaiting();
    }
}
