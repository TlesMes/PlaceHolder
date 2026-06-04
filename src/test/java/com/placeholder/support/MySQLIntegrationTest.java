package com.placeholder.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 통합 테스트 베이스. MySQL 컨테이너를 싱글톤으로 한 번만 기동하고 JVM 종료까지 유지한다.
 *
 * <p>@Testcontainers + @Container(static) 패턴은 컨테이너를 "클래스 단위"로 stop 한다.
 * 그런데 동일한 @SpringBootTest 설정을 쓰는 여러 테스트 클래스는 같은 ApplicationContext를
 * 캐시 공유하므로, 한 클래스가 끝나며 컨테이너를 stop 하면 다음 클래스가 캐시된 컨텍스트의
 * 죽은 커넥션을 잡아 "Connection is not available" 타임아웃이 난다.
 * 컨테이너를 stop 하지 않는 싱글톤으로 두어 이 문제를 제거한다.
 */
public abstract class MySQLIntegrationTest {

    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    static {
        mysql.start();
    }

    // 싱글톤 컨테이너는 JVM 전체에서 데이터를 공유하고 통합 테스트는 데이터를 정리하지 않는다.
    // 클래스별 SEQ는 클래스 간 이메일 충돌(예: provider-1@test.com 중복)을 일으키므로,
    // 모든 통합 테스트가 공유하는 JVM 단위 시퀀스로 유니크 값을 발급한다.
    private static final AtomicInteger SHARED_SEQ = new AtomicInteger();

    protected static int uniqueId() {
        return SHARED_SEQ.incrementAndGet();
    }
}
