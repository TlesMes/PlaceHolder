package com.placeholder.seat;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.seat.dto.SeatHoldResponse;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.SeatNotAvailableException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SeatHoldConcurrencyTest extends MySQLIntegrationTest {

    @Autowired SeatService seatService;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("동일 좌석에 동시 홀드 요청이 몰려도 정확히 한 명만 성공한다")
    void concurrent_hold_only_one_succeeds() throws InterruptedException {
        // given - 기본 커넥션 풀(10) 안에서 모든 스레드가 동시에 락 경합하도록 스레드 수를 둔다.
        // 풀보다 많은 스레드를 쓰면 DB 행 락이 아니라 커넥션 획득에서 막혀 테스트가 불안정해진다.
        int threadCount = 10;
        Event event = persistEvent();
        Seat seat = persistSeat(event, Seat.SeatStatus.AVAILABLE, null, null);
        List<Long> bookerIds = persistBookers(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        // when - 모든 스레드가 동시에 같은 좌석을 홀드 시도
        for (int i = 0; i < threadCount; i++) {
            Long bookerId = bookerIds.get(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    seatService.holdSeat(seat.getId(), bookerId);
                    successCount.incrementAndGet();
                } catch (SeatNotAvailableException e) {
                    conflictCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();          // 동시 출발
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threadCount - 1);

        Seat held = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(Seat.SeatStatus.HELD);
        assertThat(held.getHeldBy()).isNotNull();
        assertThat(held.getHeldUntil()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("held_until이 만료된 HELD 좌석은 새 예약자가 재점유할 수 있다")
    void expired_hold_is_reacquired() {
        // given - 1시간 전에 만료된 HELD 좌석
        Event event = persistEvent();
        List<Long> bookers = persistBookers(2);
        Long oldBookerId = bookers.get(0);
        Long newBookerId = bookers.get(1);
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, oldBookerId,
                LocalDateTime.now().minusHours(1));

        // when
        SeatHoldResponse response = seatService.holdSeat(seat.getId(), newBookerId);

        // then - 새 예약자가 점유, heldBy 교체
        assertThat(response.getHeldBy()).isEqualTo(newBookerId);
        assertThat(response.getStatus()).isEqualTo("HELD");
        Seat reheld = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reheld.getHeldBy().getId()).isEqualTo(newBookerId);
        assertThat(reheld.getHeldUntil()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("CONFIRMED 좌석은 홀드할 수 없다 (409)")
    void confirmed_seat_cannot_be_held() {
        // given
        Event event = persistEvent();
        Long bookerId = persistBookers(1).get(0);
        Seat seat = persistSeat(event, Seat.SeatStatus.CONFIRMED, null, null);

        // when & then
        assertThatThrownBy(() -> seatService.holdSeat(seat.getId(), bookerId))
                .isInstanceOf(SeatNotAvailableException.class);
    }

    @Test
    @DisplayName("미만료 HELD 좌석은 다른 예약자가 홀드할 수 없다 (409)")
    void active_hold_cannot_be_held_by_another() {
        // given - 아직 유효한 HELD 좌석
        Event event = persistEvent();
        Long holderId = persistBookers(1).get(0);
        Long otherId = persistBookers(1).get(0);
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, holderId,
                LocalDateTime.now().plusMinutes(5));

        // when & then
        assertThatThrownBy(() -> seatService.holdSeat(seat.getId(), otherId))
                .isInstanceOf(SeatNotAvailableException.class);
    }

    // --- 테스트 데이터 셋업 헬퍼 ---

    @Transactional
    Event persistEvent() {
        User provider = userRepository.save(User.builder()
                .email("provider-" + uniq() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        return eventRepository.save(Event.builder()
                .provider(provider)
                .title("동시성 테스트 이벤트")
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .build());
    }

    @Transactional
    Seat persistSeat(Event event, Seat.SeatStatus status, Long heldById, LocalDateTime heldUntil) {
        User heldBy = heldById == null ? null : userRepository.findById(heldById).orElseThrow();
        Seat seat = Seat.builder()
                .event(event)
                .label("A-" + uniq())
                .price(10000)
                .status(status)
                .heldBy(heldBy)
                .heldUntil(heldUntil)
                .build();
        return seatRepository.save(seat);
    }

    @Transactional
    List<Long> persistBookers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User booker = userRepository.save(User.builder()
                    .email("booker-" + uniq() + "@test.com")
                    .passwordHash("hash")
                    .role(User.UserRole.BOOKER)
                    .build());
            ids.add(booker.getId());
        }
        return ids;
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static int uniq() {
        return SEQ.incrementAndGet();
    }
}
