package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentCancelReconciliationService;
import com.placeholder.domain.payment.service.PaymentCancelReconciliationService.CancelReconcileResult;
import com.placeholder.domain.payment.service.PaymentSettlementService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 역방향 대사 × 사용자 취소 경합 (ADR-019).
 *
 * <p>대사는 후보를 <b>락 없이</b> 읽고, 트랜잭션 밖에서 토스를 호출한 뒤, 마지막 트랜잭션에서
 * 확인 기록을 남긴다. 그 사이에 사용자가 <b>새 부분 취소</b>를 하면 누적 취소액이 늘어나는데,
 * 대사가 그대로 스탬프를 찍으면 <b>아직 토스에 도달하지도 않은 새 취소까지 "확인됨"으로 표시된다</b> —
 * 대사가 스스로 크래시 창을 만들어 덮어버리는 셈이고, 그 창은 다시는 발견되지 않는다.
 *
 * <p>{@code confirmCancelIfUnchanged}의 누적액 가드가 이것을 막는지 확인한다. 토스 호출 도중
 * 사용자 취소를 끼워 넣어 그 순간을 결정론적으로 재현한다(래치 인터리빙).
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentCancelReconciliationConcurrencyTest extends MySQLIntegrationTest {

    @Autowired PaymentCancelReconciliationService reconciliationService;
    @Autowired PaymentSettlementService settlementService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean TossPaymentClient tossClient;

    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from payment_orders");
    }

    @RepeatedTest(5)
    @DisplayName("대사 진행 중 도착한 새 취소를 확인 처리하지 않는다 (누적액 가드)")
    void reconcile_userCancelsDuringTossCall_doesNotStampNewCancel() throws Exception {
        Long bookerId = persistBooker(10_000);
        // 5,000이 취소 기록됐지만 토스 확인 전 = 대사 후보. 잔여 5,000은 아직 취소 가능하다.
        String orderId = persistCanceledOrder(bookerId, 10_000, 5_000);

        // 토스는 아직 아무것도 취소하지 않았다 → 대사는 차액 5,000을 재전송하려 한다
        when(tossClient.findByOrderId(orderId)).thenReturn(Optional.of(
                new TossPaymentResult("pk_" + orderId, orderId, "DONE", 10_000, 10_000)));

        CountDownLatch tossCallStarted = new CountDownLatch(1);
        CountDownLatch userCancelDone = new CountDownLatch(1);

        // 토스 취소 호출 도중에 사용자 취소가 끼어드는 순간을 재현한다
        when(tossClient.cancel(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            tossCallStarted.countDown();
            userCancelDone.await(5, TimeUnit.SECONDS);
            return new TossPaymentResult("pk_" + orderId, orderId, "PARTIAL_CANCELED", 10_000, 5_000);
        });

        AtomicReference<CancelReconcileResult> result = new AtomicReference<>();
        Thread reconciler = new Thread(() -> {
            LocalDateTime now = LocalDateTime.now();
            result.set(reconciliationService.reconcile(
                    now.minusHours(24), now.minusMinutes(5), false));
        });
        reconciler.start();

        assertThat(tossCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // 사용자가 잔여 5,000을 추가로 취소 → 누적 취소액이 10,000으로 바뀐다
        settlementService.prepareCancel(orderId, bookerId, 7);
        userCancelDone.countDown();

        reconciler.join(10_000);

        assertThat(result.get().skipped())
                .as("장부가 바뀌었으므로 대사는 손대지 않고 다음 주기로 넘긴다")
                .isEqualTo(1);
        assertThat(orderOf(orderId).getCanceledAmount()).isEqualTo(10_000);
        assertThat(orderOf(orderId).getCancelConfirmedAt())
                .as("새로 들어온 5,000은 아직 토스에 가지도 않았다 — 확인 처리되면 영영 발견되지 않는다")
                .isNull();
    }

    // --- 헬퍼 ---

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

    private String persistCanceledOrder(Long userId, int amount, int canceledAmount) {
        String orderId = UUID.randomUUID().toString();
        User user = userRepository.findById(userId).orElseThrow();

        PaymentOrder order = PaymentOrder.builder()
                .orderId(orderId).user(user).amount(amount).build();
        order.markDone("pk_" + orderId);
        order.markCanceled(canceledAmount);
        paymentOrderRepository.save(order);

        // min-age(5분) 바깥으로 밀어 대사 후보가 되게 한다
        int updated = jdbcTemplate.update(
                "update payment_orders set canceled_at = ? where order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(1)), orderId);
        assertThat(updated).as("backdate 대상 주문이 갱신되어야 한다").isEqualTo(1);
        return orderId;
    }

    private PaymentOrder orderOf(String orderId) {
        return paymentOrderRepository.findByOrderId(orderId).orElseThrow();
    }
}
