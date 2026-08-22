package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentCancelReconciliationService;
import com.placeholder.domain.payment.service.PaymentReconciliationService;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 같은 대사 잡이 <b>두 번 동시에</b> 도는 상황을 검증한다 (마스터플랜 E-4).
 *
 * <p>스케줄러는 인스턴스마다 각자 돈다. 앱을 2대로 늘리는 순간 04:00 대사 잡은 두 곳에서 동시에
 * 시작하고, 둘은 같은 후보 집합을 스캔한다. 지금까지의 경합 테스트는 <b>서로 다른 주체</b>끼리의
 * 충돌만 봤다 — 대사 × 웹훅({@code ReconciliationWebhookRaceTest}), 대사 × 사용자 취소
 * ({@code PaymentCancelReconciliationConcurrencyTest}). <b>대사 × 대사는 비어 있었다.</b>
 *
 * <p>여기서 인스턴스를 실제로 2개 띄우지 않는 이유는, 검증하려는 것이 프로세스 경계가 아니라
 * <b>같은 DB 행을 동시에 건드리는 두 실행</b>이기 때문이다. 그 조건은 스레드 2개로 충분히
 * 재현되고(오히려 래치로 겹치는 순간을 강제할 수 있어 더 확실하다), 프로세스를 나눠도
 * 정합성을 지키는 주체는 여전히 DB 락과 멱등 코어다 — 앱 메모리에는 이 경로의 상태가 없다.
 *
 * <p><b>음성 대조로 확인한 것 — 방어선은 두 겹이다.</b> 서비스의 멱등 판정
 * ({@code PaymentSettlementService.settle}의 {@code isSettled} 체크)만 무력화했을 때는
 * 이 테스트가 <b>여전히 통과했다.</b> 엔티티의 상태 전이 가드({@code PaymentOrder.markDone}이
 * READY가 아니면 예외)가 두 번째 적립을 막고 있었기 때문이다. 둘을 모두 무력화하자
 * 잔액이 20,000으로 갈리며 5/5 실패했다 — 이 테스트가 이중 적립을 실제로 잡아낸다는 근거다.
 *
 * <p>두 가드의 성격이 다르다는 점이 설계상 의미가 있다: 서비스 쪽은 <b>조용한 no-op</b>(정상 경로,
 * 웹훅이 뒤늦게 와도 에러가 아니다)이고, 엔티티 쪽은 <b>시끄러운 예외</b>(도달하면 안 되는 전이).
 * 앞의 것이 사라져도 뒤의 것이 남아 돈이 새지 않는다.
 *
 * <p>⚠️ 이 테스트가 덮지 <b>않는</b> 것: 앱 메모리에 상태를 두는 경로(대기열의 deficit 장부,
 * ADR-017)는 인스턴스마다 따로 갖게 되므로 여기서 증명되지 않는다. 그쪽은 알려진 한계다.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationDuplicateRunTest extends MySQLIntegrationTest {

    @Autowired PaymentReconciliationService reconciliationService;
    @Autowired PaymentCancelReconciliationService cancelReconciliationService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean TossPaymentClient tossClient;

    /** 대사는 구간 전체를 스캔하므로 다른 테스트가 남긴 주문이 섞이지 않도록 매번 비운다. */
    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from payment_orders");
    }

    @RepeatedTest(5)
    @DisplayName("정방향 대사 잡 2개 동시 실행 → 적립 정확히 1회 (인스턴스 2대 전제)")
    void forwardReconciliation_runTwiceConcurrently_creditsExactlyOnce() throws Exception {
        Long bookerId = persistBooker(0);
        String orderId = persistReadyOrder(bookerId, 10_000, LocalDateTime.now().minusHours(1));

        // 두 실행이 확실히 겹치게 만든다 — 둘 다 후보를 집어 든 뒤에야 어느 쪽도 커밋으로 못 넘어간다.
        // 이 장치가 없으면 한쪽이 먼저 끝나 다른 쪽은 후보를 못 보고, 그러면 검증하려던 경합이
        // 일어나지 않은 채로 테스트가 통과한다(이미 증명된 순차 멱등성만 재확인하는 꼴).
        CyclicBarrier bothInside = new CyclicBarrier(2);
        when(tossClient.findByOrderId(orderId)).thenAnswer(invocation -> {
            bothInside.await(10, TimeUnit.SECONDS);
            return Optional.of(new TossPaymentResult("pk_1", orderId, "DONE", 10_000));
        });

        LocalDateTime now = LocalDateTime.now();
        runConcurrently(
                () -> reconciliationService.reconcile(now.minusHours(2), now.minusMinutes(5), false),
                () -> reconciliationService.reconcile(now.minusHours(2), now.minusMinutes(5), false));

        // 경합이 실제로 일어났음을 먼저 못 박는다 — 한쪽만 후보를 봤다면 이 테스트는
        // 동시 실행이 아니라 순차 멱등성을 재확인한 것에 불과하다(조용한 위양성).
        verify(tossClient, times(2))
                .findByOrderId(orderId);

        // 두 실행이 같은 주문을 봤지만 멱등 코어(settle)가 두 번째를 no-op으로 흘린다
        assertThat(balanceOf(bookerId))
                .as("두 번 적립되면 없던 돈이 생긴다")
                .isEqualTo(10_000);
        assertThat(pointTransactionRepository.findByTypeAndUserId(TransactionType.CHARGE, bookerId))
                .hasSize(1);
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.DONE);
    }

    @RepeatedTest(5)
    @DisplayName("역방향 대사 잡 2개 동시 실행 → 포인트 이중 회수 없음, 취소는 같은 멱등 키로만 나간다")
    void cancelReconciliation_runTwiceConcurrently_doesNotDoubleRefund() throws Exception {
        Long bookerId = persistBooker(0);
        // 5,000이 취소 기록됐으나 토스 확인 전 = 역방향 대사 후보 (①만 커밋되고 죽은 상태)
        String orderId = persistCanceledOrder(bookerId, 10_000, 5_000);

        // 토스는 아직 취소하지 않았다 → 두 실행 모두 차액 5,000을 재전송하려 든다.
        // 배리어로 둘 다 후보를 집어 든 상태를 만든 뒤 진행시킨다(위 테스트와 같은 이유).
        CyclicBarrier bothInside = new CyclicBarrier(2);
        when(tossClient.findByOrderId(orderId)).thenAnswer(invocation -> {
            bothInside.await(10, TimeUnit.SECONDS);
            return Optional.of(new TossPaymentResult("pk_" + orderId, orderId, "DONE", 10_000, 10_000));
        });
        when(tossClient.cancel(any(), any(), anyInt(), any())).thenReturn(
                new TossPaymentResult("pk_" + orderId, orderId, "PARTIAL_CANCELED", 10_000, 5_000));

        LocalDateTime now = LocalDateTime.now();
        runConcurrently(
                () -> cancelReconciliationService.reconcile(now.minusHours(24), now.minusMinutes(5), false),
                () -> cancelReconciliationService.reconcile(now.minusHours(24), now.minusMinutes(5), false));

        // 위 테스트와 같은 이유 — 두 실행이 같은 후보를 집었음을 먼저 확인한다
        verify(tossClient, times(2))
                .findByOrderId(orderId);

        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElseThrow();
        assertThat(order.getCanceledAmount())
                .as("대사는 이미 기록된 취소를 재전송할 뿐 새로 취소하지 않는다")
                .isEqualTo(5_000);
        assertThat(order.getCancelConfirmedAt()).isNotNull();
        assertThat(balanceOf(bookerId))
                .as("역방향 대사는 포인트를 건드리지 않는다 (회수는 이미 ①에서 끝났다)")
                .isZero();
        assertThat(pointTransactionRepository.findByTypeAndUserId(TransactionType.REFUND, bookerId))
                .as("대사가 REFUND를 새로 쓰면 장부가 실제 환불보다 커진다")
                .isEmpty();

        // 두 실행이 겹쳐 토스를 각자 불렀더라도, 멱등 키가 같으면 토스가 두 번째를 재생으로 처리한다.
        // 키는 누적 취소액에서 나오므로(ADR-019) 장부가 안 바뀐 이상 동일해야 한다.
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(tossClient, atLeastOnce())
                .cancel(any(), any(), anyInt(), keyCaptor.capture());
        assertThat(keyCaptor.getAllValues())
                .as("키가 갈리면 토스가 서로 다른 요청으로 보고 실제로 두 번 환불한다")
                .containsOnly(keyCaptor.getAllValues().get(0));
    }

    // --- 헬퍼 ---

    /** 두 작업을 래치로 정렬해 같은 순간에 출발시킨다. */
    private void runConcurrently(Runnable first, Runnable second) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        for (Runnable action : new Runnable[] {first, second}) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    action.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // 한쪽이 락 경합으로 실패해도 정합성은 최종 상태로 판정한다.
                    // 다만 삼켜서 원인을 잃지 않도록 남긴다 (D-3 하네스 함정 ②).
                    System.out.println("대사 실행 하나가 예외로 끝남: " + e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("두 대사 실행이 끝나야 한다").isTrue();
        executor.shutdownNow();
    }

    private Long persistBooker(int paidBalance) {
        User booker = userRepository.save(User.builder()
                .email("booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(
                BookerAccount.builder().user(booker).paidBalance(paidBalance).build());
        return booker.getId();
    }

    private String persistReadyOrder(Long userId, int amount, LocalDateTime createdAt) {
        String orderId = UUID.randomUUID().toString();
        User user = userRepository.findById(userId).orElseThrow();
        paymentOrderRepository.save(PaymentOrder.builder()
                .orderId(orderId).user(user).amount(amount).build());
        int updated = jdbcTemplate.update(
                "update payment_orders set created_at = ? where order_id = ?",
                Timestamp.valueOf(createdAt), orderId);
        assertThat(updated).as("backdate 대상 주문이 갱신되어야 한다").isEqualTo(1);
        return orderId;
    }

    private String persistCanceledOrder(Long userId, int amount, int canceledAmount) {
        String orderId = UUID.randomUUID().toString();
        User user = userRepository.findById(userId).orElseThrow();

        PaymentOrder order = PaymentOrder.builder()
                .orderId(orderId).user(user).amount(amount).build();
        order.markDone("pk_" + orderId);
        order.markCanceled(canceledAmount);
        paymentOrderRepository.save(order);

        int updated = jdbcTemplate.update(
                "update payment_orders set canceled_at = ? where order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(1)), orderId);
        assertThat(updated).as("backdate 대상 주문이 갱신되어야 한다").isEqualTo(1);
        return orderId;
    }

    private int balanceOf(Long userId) {
        return bookerAccountRepository.findByUserId(userId).orElseThrow().getBalance();
    }
}
