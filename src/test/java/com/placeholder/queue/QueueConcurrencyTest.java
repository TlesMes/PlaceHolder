package com.placeholder.queue;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.queue.service.QueueAdmissionService;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.QueueAdmissionRequiredException;
import com.placeholder.global.exception.custom.SeatNotAvailableException;
import com.placeholder.support.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기열 동시성 검증 (E-1 5단계).
 *
 * <ul>
 *   <li><b>스케줄러 race:</b> admitWaiting()을 여러 스레드가 동시에 호출해도 ZPOPMIN 원자성으로
 *       각 대기자는 정확히 한 번만 입장(중복 토큰 없음, 유실 없음) — 토큰 보유 ⊕ 대기 중 분할이 깨지지 않는다.</li>
 *   <li><b>토큰 + 락 합성:</b> 입장 토큰을 가진 다수가 같은 좌석을 동시에 hold 해도 비관적 락(ADR-008)이
 *       정확히 한 명만 성공시킨다. 대기열은 트래픽만 셰이핑하고 정합성은 락이 책임진다는 2층 구조 검증.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class QueueConcurrencyTest extends RedisIntegrationTest {

    private static final int CAPACITY = 8; // test 프로파일 queue.max-concurrent-holds

    @Autowired QueueAdmissionService admissionService;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired SeatService seatService;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void flush() {
        var keys = redis.keys("queue:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        var tokens = redis.keys("entry:*");
        if (tokens != null && !tokens.isEmpty()) redis.delete(tokens);
    }

    @Test
    @DisplayName("스케줄러 동시 실행: 각 대기자는 정확히 한 번만 입장 (ZPOPMIN 원자성)")
    void concurrent_admission_partitionsCleanly() throws InterruptedException {
        long eventId = uniqueId();
        int waiting = 50;
        for (long u = 1; u <= waiting; u++) {
            queueRepository.enqueue(eventId, u, 1_000L + u);
        }

        int threads = 12;
        runConcurrently(threads, () -> admissionService.admitWaiting());

        // 분할 불변식: 모든 사용자는 (토큰 보유) XOR (대기 중) — 둘 다이거나 둘 다 아님이 없어야 한다.
        int tokenCount = 0;
        int queued = 0;
        for (long u = 1; u <= waiting; u++) {
            boolean hasToken = queueRepository.hasEntryToken(eventId, u);
            boolean inQueue = queueRepository.rank(eventId, u) != null;
            assertThat(hasToken ^ inQueue)
                    .as("user %d must be exactly one of {admitted, waiting}", u)
                    .isTrue();
            if (hasToken) tokenCount++; else queued++;
        }
        assertThat(tokenCount + queued).isEqualTo(waiting);          // 유실 없음
        assertThat(queueRepository.size(eventId)).isEqualTo(queued); // 대기열 크기 일치
        assertThat(tokenCount).isBetween(CAPACITY, waiting);         // 최소 한 배치(슬롯)는 입장
    }

    @Test
    @DisplayName("토큰 보유자 다수가 같은 좌석 동시 hold: 락이 한 명만 성공시킨다")
    void concurrent_hold_withTokens_onlyOneWins() throws InterruptedException {
        int n = 10; // 커넥션 풀(10) 내에서 락 경합
        Event event = persistEvent(true);
        Seat seat = persistSeat(event);
        List<Long> bookers = persistBookers(n);
        for (Long b : bookers) {
            queueRepository.issueEntryToken(event.getId(), b, Duration.ofMinutes(5));
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger admissionDenied = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (Long bookerId : bookers) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    seatService.holdSeat(seat.getId(), bookerId);
                    success.incrementAndGet();
                } catch (SeatNotAvailableException e) {
                    conflict.incrementAndGet();
                } catch (QueueAdmissionRequiredException e) {
                    admissionDenied.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(n - 1);
        assertThat(admissionDenied.get()).isZero(); // 전원 토큰 보유 → 게이트 통과
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(Seat.SeatStatus.HELD);
    }

    // --- 동시 실행 하니스 ---

    private void runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();
    }

    // --- 셋업 헬퍼 ---

    @Transactional
    Event persistEvent(boolean queueEnabled) {
        User provider = userRepository.save(User.builder()
                .email("provider-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        return eventRepository.save(Event.builder()
                .provider(provider)
                .title("동시성 이벤트 " + uniqueId())
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.SECONDS))
                .queueEnabled(queueEnabled)
                .build());
    }

    @Transactional
    Seat persistSeat(Event event) {
        return seatRepository.save(Seat.builder()
                .event(event)
                .label("A-" + uniqueId())
                .price(10_000)
                .status(Seat.SeatStatus.AVAILABLE)
                .build());
    }

    @Transactional
    List<Long> persistBookers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(userRepository.save(User.builder()
                    .email("booker-" + uniqueId() + "@test.com")
                    .passwordHash("hash")
                    .role(User.UserRole.BOOKER)
                    .build()).getId());
        }
        return ids;
    }
}
