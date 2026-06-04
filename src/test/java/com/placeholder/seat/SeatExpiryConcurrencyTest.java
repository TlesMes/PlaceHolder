package com.placeholder.seat;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.reservation.service.ReservationService;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.seat.service.SeatExpiryService;
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

/**
 * Phase C-3/C-4: 자동 만료 해제(SeatExpiryService)의 단위·동시성 정합성 테스트.
 *
 * 스케줄러 자동 발화 대신 releaseExpiredSeats()를 직접 호출해 결정적으로 검증한다
 * (application-test.yml에서 scan-interval-ms를 매우 크게 둬 스케줄러가 끼어들지 않게 함).
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class SeatExpiryConcurrencyTest extends MySQLIntegrationTest {

    @Autowired SeatExpiryService seatExpiryService;
    @Autowired SeatService seatService;
    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired ProviderAccountRepository providerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("만료된 HELD 좌석은 만료 처리로 AVAILABLE로 복귀한다")
    void expired_held_seats_are_released() {
        // given - 1시간 전에 만료된 HELD 좌석 5개
        Event event = persistEvent();
        Long holderId = persistBooker();
        List<Long> seatIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Seat seat = persistSeat(event, Seat.SeatStatus.HELD, holderId,
                    LocalDateTime.now().minusHours(1), 10_000);
            seatIds.add(seat.getId());
        }

        // when
        int released = seatExpiryService.releaseExpiredSeats();

        // then - 전부 해제, heldBy/heldUntil 클리어
        assertThat(released).isEqualTo(5);
        for (Long id : seatIds) {
            Seat seat = seatRepository.findById(id).orElseThrow();
            assertThat(seat.getStatus()).isEqualTo(Seat.SeatStatus.AVAILABLE);
            assertThat(seat.getHeldBy()).isNull();
            assertThat(seat.getHeldUntil()).isNull();
        }
    }

    @Test
    @DisplayName("미만료 HELD 좌석은 만료 처리가 건드리지 않는다")
    void active_held_seat_is_not_released() {
        // given - held_until이 미래인 HELD 좌석
        Event event = persistEvent();
        Long holderId = persistBooker();
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, holderId,
                LocalDateTime.now().plusMinutes(10), 10_000);

        // when
        int released = seatExpiryService.releaseExpiredSeats();

        // then - 변화 없음
        assertThat(released).isEqualTo(0);
        Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Seat.SeatStatus.HELD);
        assertThat(reloaded.getHeldUntil()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("CONFIRMED 좌석은 만료 처리가 건드리지 않는다")
    void confirmed_seat_is_not_released() {
        // given - CONFIRMED 좌석 (held_until 없음 → 만료 후보에 잡히지 않음)
        Event event = persistEvent();
        Seat seat = persistSeat(event, Seat.SeatStatus.CONFIRMED, null, null, 10_000);

        // when
        int released = seatExpiryService.releaseExpiredSeats();

        // then
        assertThat(released).isEqualTo(0);
        Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Seat.SeatStatus.CONFIRMED);
    }

    @Test
    @DisplayName("만료된 좌석에 만료 처리와 확정이 동시에 일어나도 정합성이 깨지지 않는다")
    void expiry_vs_confirm_keeps_consistency() throws InterruptedException {
        // given - 이미 만료된 HELD 좌석. 확정은 만료된 홀드를 거부해야 하고(validateHold),
        // 만료 처리는 AVAILABLE로 되돌린다. 두 작업이 동시에 일어나도
        // "차감됐는데 좌석은 AVAILABLE" 같은 불일치가 없어야 한다.
        int price = 5_000;
        int initialBalance = 100_000;
        Event event = persistEvent();
        Long bookerId = persistBookerWithBalance(initialBalance);
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, bookerId,
                LocalDateTime.now().minusSeconds(1), price);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger confirmSuccess = new AtomicInteger();

        // (a) 만료 처리
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                seatExpiryService.releaseExpiredSeats();
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });
        // (b) 정당한 holder의 확정 시도 (단, 홀드가 만료됐으므로 거부돼야 정상)
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                reservationService.confirmReservation(seat.getId(), bookerId);
                confirmSuccess.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then - 만료된 홀드 확정은 성공할 수 없다.
        assertThat(confirmSuccess.get()).isEqualTo(0);

        Seat finalSeat = seatRepository.findById(seat.getId()).orElseThrow();
        BookerAccount account = bookerAccountRepository.findByUserId(bookerId).orElseThrow();

        // 확정이 실패했으므로 좌석은 AVAILABLE(만료 처리됨)이고 잔액 차감/정산 흔적이 없어야 한다.
        // (count()는 다른 테스트가 남긴 행과 섞이므로 이 booker 기준으로만 검증)
        assertThat(finalSeat.getStatus()).isEqualTo(Seat.SeatStatus.AVAILABLE);
        assertThat(account.getBalance()).isEqualTo(initialBalance);
        assertThat(pointTransactionRepository.findByUserId(bookerId)).isEmpty();
    }

    @Test
    @DisplayName("만료된 좌석에 만료 처리와 신규 홀드가 동시에 일어나도 최종 상태가 일관된다")
    void expiry_vs_new_hold_keeps_consistency() throws InterruptedException {
        // given - 막 만료된 HELD 좌석 1개에 N명이 동시에 신규 홀드 + 만료 처리도 동시 실행
        int holderThreads = 9;
        Event event = persistEvent();
        Long oldHolderId = persistBooker();
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, oldHolderId,
                LocalDateTime.now().minusSeconds(1), 10_000);
        List<Long> newBookers = persistBookers(holderThreads);

        int total = holderThreads + 1; // 홀드 스레드 + 만료 처리 스레드 1개
        ExecutorService executor = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger holdSuccess = new AtomicInteger();

        for (int i = 0; i < holderThreads; i++) {
            Long bookerId = newBookers.get(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    seatService.holdSeat(seat.getId(), bookerId);
                    holdSuccess.incrementAndGet();
                } catch (SeatNotAvailableException ignored) {
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                seatExpiryService.releaseExpiredSeats();
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then - 최종 상태는 일관돼야 한다.
        // 홀드가 한 명이라도 성공했으면 HELD(heldBy/heldUntil 미래), 아니면 AVAILABLE.
        // 둘 중 어느 쪽이든 다중 홀드 성공(중복 점유)은 없어야 한다.
        assertThat(holdSuccess.get()).isLessThanOrEqualTo(1);

        Seat finalSeat = seatRepository.findById(seat.getId()).orElseThrow();
        if (holdSuccess.get() == 1) {
            assertThat(finalSeat.getStatus()).isEqualTo(Seat.SeatStatus.HELD);
            assertThat(finalSeat.getHeldBy()).isNotNull();
            assertThat(finalSeat.getHeldUntil()).isAfter(LocalDateTime.now());
        } else {
            assertThat(finalSeat.getStatus()).isEqualTo(Seat.SeatStatus.AVAILABLE);
            assertThat(finalSeat.getHeldBy()).isNull();
            assertThat(finalSeat.getHeldUntil()).isNull();
        }
    }

    // --- 테스트 데이터 셋업 헬퍼 ---

    @Transactional
    Event persistEvent() {
        User provider = userRepository.save(User.builder()
                .email("provider-" + uniq() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        providerAccountRepository.save(ProviderAccount.builder()
                .user(provider)
                .build());
        return eventRepository.save(Event.builder()
                .provider(provider)
                .title("만료 동시성 테스트")
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .build());
    }

    @Transactional
    Long persistBooker() {
        return persistBookerWithBalance(0);
    }

    @Transactional
    Long persistBookerWithBalance(int balance) {
        User booker = userRepository.save(User.builder()
                .email("booker-" + uniq() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder()
                .user(booker)
                .balance(balance)
                .build());
        return booker.getId();
    }

    @Transactional
    List<Long> persistBookers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(persistBookerWithBalance(0));
        }
        return ids;
    }

    @Transactional
    Seat persistSeat(Event event, Seat.SeatStatus status, Long heldById,
                     LocalDateTime heldUntil, int price) {
        User heldBy = heldById == null ? null : userRepository.findById(heldById).orElseThrow();
        return seatRepository.save(Seat.builder()
                .event(event)
                .label("A-" + uniq())
                .price(price)
                .status(status)
                .heldBy(heldBy)
                .heldUntil(heldUntil)
                .build());
    }

    private static int uniq() {
        return uniqueId();
    }
}
