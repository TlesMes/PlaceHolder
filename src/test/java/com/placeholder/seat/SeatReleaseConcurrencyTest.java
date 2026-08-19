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
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이탈 시 좌석 반환(release)과 확정(confirm)이 같은 좌석 행을 두고 동시에 일어나도
 * 비관적 락으로 직렬화돼 정합성이 깨지지 않음을 검증한다 (ADR-016, ADR-008).
 *
 * 최종 상태는 CONFIRMED(확정 승리) xor AVAILABLE(반환 승리)로 결정적이어야 하고,
 * 잔액·정산 흔적이 그 상태와 정확히 일치해야 한다 — "차감됐는데 좌석은 AVAILABLE" 같은 불일치 금지.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class SeatReleaseConcurrencyTest extends MySQLIntegrationTest {

    @Autowired SeatService seatService;
    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired ProviderAccountRepository providerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("같은 좌석에 반환과 확정이 동시에 일어나도 최종 상태와 잔액이 일관된다")
    void release_vs_confirm_keeps_consistency() throws InterruptedException {
        // 타이밍에 따라 확정/반환 중 어느 쪽이 락을 먼저 잡을지 달라지므로 반복 시행으로 양 순서를 노출한다.
        for (int iter = 0; iter < 20; iter++) {
            int price = 5_000;
            int initialBalance = 100_000;
            Event event = persistEvent();
            Long bookerId = persistBookerWithBalance(initialBalance);
            Seat seat = persistSeat(event, Seat.SeatStatus.HELD, bookerId,
                    LocalDateTime.now().plusMinutes(5), price);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger confirmSuccess = new AtomicInteger();

            // (a) 이탈 반환
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    seatService.releaseSeat(seat.getId(), bookerId);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
            // (b) 확정
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

            Seat finalSeat = seatRepository.findById(seat.getId()).orElseThrow();
            BookerAccount account = bookerAccountRepository.findByUserId(bookerId).orElseThrow();

            if (confirmSuccess.get() == 1) {
                // 확정 승리: 좌석 CONFIRMED, 잔액 1회 차감, 반환은 no-op이었어야 한다.
                assertThat(finalSeat.getStatus()).isEqualTo(Seat.SeatStatus.CONFIRMED);
                assertThat(account.getBalance()).isEqualTo(initialBalance - price);
                assertThat(pointTransactionRepository.findByUserId(bookerId)).isNotEmpty();
            } else {
                // 반환 승리: 좌석 AVAILABLE, 확정은 거부돼 잔액 차감·정산 흔적이 없어야 한다.
                assertThat(finalSeat.getStatus()).isEqualTo(Seat.SeatStatus.AVAILABLE);
                assertThat(finalSeat.getHeldBy()).isNull();
                assertThat(account.getBalance()).isEqualTo(initialBalance);
                assertThat(pointTransactionRepository.findByUserId(bookerId)).isEmpty();
            }
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
                .title("반환-확정 동시성 테스트")
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .build());
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
                .paidBalance(balance)
                .build());
        return booker.getId();
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
