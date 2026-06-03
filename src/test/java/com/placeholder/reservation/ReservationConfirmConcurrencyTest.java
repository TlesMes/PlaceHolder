package com.placeholder.reservation;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.domain.reservation.service.ReservationService;
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

@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class ReservationConfirmConcurrencyTest extends MySQLIntegrationTest {

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired ProviderAccountRepository providerAccountRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("동일 HELD 좌석에 동시 확정 요청이 몰려도 정확히 한 명만 성공한다")
    void concurrent_confirm_only_one_succeeds() throws InterruptedException {
        // given - 한 예약자가 홀드한 좌석에 동일 예약자가 10개 스레드로 동시에 확정 시도
        // (실제로는 같은 bookerId이므로 락 경합 + 첫 번째 성공 후 나머지는 CONFIRMED 상태에서 실패)
        int threadCount = 10;
        int price = 5_000;
        Event event = persistEvent();
        Long bookerId = persistBookerWithBalance(event, price * threadCount);
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, bookerId,
                LocalDateTime.now().plusMinutes(10), price);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    reservationService.confirmReservation(seat.getId(), bookerId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then - 정확히 1번만 성공
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);

        Seat confirmed = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(Seat.SeatStatus.CONFIRMED);
    }

    @Test
    @DisplayName("동시 확정 시도에서 잔액은 정확히 1회만 차감된다")
    void concurrent_confirm_deducts_balance_exactly_once() throws InterruptedException {
        // given
        int threadCount = 10;
        int price = 5_000;
        int initialBalance = price * threadCount; // 충분한 잔액이라도 1번만 차감돼야 함
        Event event = persistEvent();
        Long bookerId = persistBookerWithBalance(event, initialBalance);
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, bookerId,
                LocalDateTime.now().plusMinutes(10), price);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    reservationService.confirmReservation(seat.getId(), bookerId);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then - 잔액은 정확히 price 한 번만 차감
        BookerAccount account = bookerAccountRepository.findByUserId(bookerId).orElseThrow();
        assertThat(account.getBalance()).isEqualTo(initialBalance - price);
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
                .title("동시성 확정 테스트")
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .build());
    }

    @Transactional
    Long persistBookerWithBalance(Event event, int balance) {
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

    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    private static int uniq() {
        return SEQ.incrementAndGet();
    }
}
