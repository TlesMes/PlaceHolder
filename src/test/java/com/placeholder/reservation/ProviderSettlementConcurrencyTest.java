package com.placeholder.reservation;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.point.entity.PointBucket;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.reservation.service.ReservationService;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
 * <b>제공자 정산액 lost update 검증.</b>
 *
 * <p>기존 확정 동시성 테스트({@link ReservationConfirmConcurrencyTest})는 <b>같은 좌석</b> 경합만
 * 본다 — 한 명만 이기는가. 그 축에서는 좌석 행 락이 모든 요청을 직렬화하므로 제공자 계정이
 * 보호되는 것처럼 보인다.
 *
 * <p>이 테스트는 축이 다르다: <b>서로 다른 예약자가 같은 제공자의 서로 다른 좌석을 동시에 확정</b>한다.
 * 이때는 좌석 락도(다른 행) 예약자 계정 락도(다른 행) 요청들을 직렬화하지 못한다.
 * 남는 것은 제공자 계정뿐인데, {@code ProviderAccountRepository}에는 잠금 조회가 없고
 * {@code ProviderAccount}에는 {@code @Version}도 없다. {@code settlementBalance += amount}는
 * read-modify-write이고 JPA 더티 체킹은 <b>절대값</b>({@code SET settlement_balance = ?})을 쓰므로,
 * 겹쳐 읽은 트랜잭션들이 서로의 적립을 덮어쓴다.
 *
 * <p><b>인기 이벤트의 정상 경로가 정확히 이 모양이다</b> — 한 제공자의 좌석 여러 개를 여러 사람이
 * 동시에 산다. 예외적 상황이 아니라 기대 트래픽이다.
 *
 * <p><b>이후 경과.</b> 위 결함은 PR #28에서 비관적 락으로 막았고, 그 락이 남긴 직렬화 상한
 * (제공자당 초당 약 85건)을 PR #31이 측정해 병목으로 판정했다. 그래서 잔액 컬럼 자체를 없애고
 * {@code SETTLE} 원장의 합으로 파생시켰다(ADR-021) — <b>갱신할 잔액이 없으므로 lost update가
 * 구조적으로 불가능해졌다.</b> 이 테스트는 그 사실의 회귀 안전망으로 남는다.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class ProviderSettlementConcurrencyTest extends MySQLIntegrationTest {

    private static final int SEAT_COUNT = 10;
    private static final int PRICE = 5_000;

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired ProviderAccountRepository providerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    /**
     * 반복 실행하는 이유: lost update는 두 트랜잭션이 제공자 잔액을 <b>겹쳐 읽어야</b> 발현한다.
     * 단발 실행에서 우연히 직렬화되면 통과해버리므로, 반복으로 재현 확률을 확보한다.
     */
    @RepeatedTest(3)
    @DisplayName("서로 다른 예약자가 같은 제공자의 좌석을 동시 확정해도 정산액이 유실되지 않는다")
    void concurrentConfirms_doNotLoseProviderSettlement() throws InterruptedException {
        // given — 제공자 1명 + 좌석 10석, 각 좌석을 서로 다른 예약자가 홀드한 상태
        Fixture f = persistFixture();

        ExecutorService executor = Executors.newFixedThreadPool(SEAT_COUNT);
        CountDownLatch ready = new CountDownLatch(SEAT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(SEAT_COUNT);
        AtomicInteger succeeded = new AtomicInteger();

        // when — 전원이 동시에 확정. 좌석도 예약자도 서로 달라 직렬화되는 지점이 없다
        for (int i = 0; i < SEAT_COUNT; i++) {
            Long seatId = f.seatIds.get(i);
            Long bookerId = f.bookerIds.get(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    reservationService.confirmReservation(seatId, bookerId);
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // 실패 원인은 아래 단정이 드러낸다
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then — 전원 성공해야 한다 (경합 대상이 없으므로)
        assertThat(succeeded.get())
                .as("서로 다른 좌석이므로 모두 확정되어야 한다")
                .isEqualTo(SEAT_COUNT);

        // 이력은 append-only라 동시 삽입에도 유실되지 않는다
        int settleRows = pointTransactionRepository
                .findByTypeAndUserId(TransactionType.SETTLE, f.providerId).size();
        assertThat(settleRows)
                .as("SETTLE 이력은 좌석 수만큼 남아야 한다")
                .isEqualTo(SEAT_COUNT);

        // 잔액은 이제 이 이력의 합이다(ADR-021). 예전엔 이 단정이 lost update 탐지기였다 —
        // 이력 10행 / 잔액 5,000처럼 갈라지는 것을 잡았다. 지금은 갈라질 두 곳이 없어
        // 사실상 "이력이 온전한가"를 다시 묻는다. 단정이 싱거워진 것 자체가 결론이다.
        assertThat(pointTransactionRepository.sumSettlementByProviderId(f.providerId))
                .as("정산 잔액(파생) 이 SETTLE 이력 합과 일치해야 한다")
                .isEqualTo((long) SEAT_COUNT * PRICE);
    }

    // --- 픽스처 ---

    private record Fixture(Long providerId, List<Long> bookerIds, List<Long> seatIds) {
    }

    private Fixture persistFixture() {
        User provider = userRepository.save(User.builder()
                .email("settle-provider-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        providerAccountRepository.save(ProviderAccount.builder().user(provider).build());

        Event event = eventRepository.save(Event.builder()
                .provider(provider)
                .title("정산 동시성 검증 이벤트")
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .build());

        List<Long> bookerIds = new ArrayList<>();
        List<Long> seatIds = new ArrayList<>();
        for (int i = 0; i < SEAT_COUNT; i++) {
            User booker = userRepository.save(User.builder()
                    .email("settle-booker-" + uniqueId() + "@test.com")
                    .passwordHash("hash")
                    .role(User.UserRole.BOOKER)
                    .build());
            // 각자 자기 계정을 쓰므로 예약자 계정 락으로는 서로 직렬화되지 않는다
            BookerAccount account = BookerAccount.builder().user(booker).build();
            account.charge(PRICE, PointBucket.PAID);
            bookerAccountRepository.save(account);

            Seat seat = seatRepository.save(Seat.builder()
                    .event(event)
                    .label("S-" + uniqueId())
                    .price(PRICE)
                    .status(Seat.SeatStatus.HELD)
                    .heldBy(booker)
                    .heldUntil(LocalDateTime.now().plusMinutes(10))
                    .build());

            bookerIds.add(booker.getId());
            seatIds.add(seat.getId());
        }

        return new Fixture(provider.getId(), bookerIds, seatIds);
    }
}
