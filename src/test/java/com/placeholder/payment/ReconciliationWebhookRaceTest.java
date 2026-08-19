package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.TossWebhookPayload;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentReconciliationService;
import com.placeholder.domain.payment.service.PaymentWebhookService;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
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

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 삼중화가 되어도 exactly-once가 유지되는지 검증 (ADR-018).
 *
 * <p>confirm × 웹훅 경합은 {@code PaymentConcurrencyTest}가 이미 증명했다. 여기서 새로 보는 건
 * <b>대사가 세 번째 경로로 합류했을 때</b>다 — 웹훅이 뒤늦게 도착하는 순간 대사 배치도 같은 주문을
 * 보정하려 들 수 있다. 두 경로 모두 멱등 코어({@code PaymentSettlementService.settle})로 수렴하고
 * 주문 행 비관적 락이 직렬화하므로 적립은 정확히 1회여야 한다.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationWebhookRaceTest extends MySQLIntegrationTest {

    @Autowired PaymentReconciliationService reconciliationService;
    @Autowired PaymentWebhookService webhookService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean TossPaymentClient tossClient;

    /** 대사는 구간 전체를 스캔하므로 다른 테스트가 남긴 READY 주문이 섞이지 않도록 매번 비운다. */
    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from payment_orders");
    }

    @RepeatedTest(5)
    @DisplayName("대사 × 웹훅 동시 도착 → 적립 정확히 1회 (삼중화에도 exactly-once 유지)")
    void reconciliationAndWebhook_race_creditsExactlyOnce() throws InterruptedException {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, LocalDateTime.now().minusHours(1));

        // 대사 경로(orderId 조회)와 웹훅 경로(paymentKey 재조회) 둘 다 DONE으로 응답
        when(tossClient.findByOrderId(orderId)).thenReturn(
                Optional.of(new TossPaymentResult("pk_1", orderId, "DONE", 10_000)));
        when(tossClient.getPayment(any())).thenReturn(
                new TossPaymentResult("pk_1", orderId, "DONE", 10_000));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        LocalDateTime now = LocalDateTime.now();
        executor.submit(() -> race(ready, start, done,
                () -> reconciliationService.reconcile(now.minusHours(2), now.minusMinutes(5), false)));
        executor.submit(() -> race(ready, start, done,
                () -> webhookService.handle(webhookPayload(orderId, "pk_1"))));

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        assertThat(pointTransactionRepository.findByTypeAndUserId(TransactionType.CHARGE, bookerId))
                .hasSize(1);
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.DONE);
    }

    private void race(CountDownLatch ready, CountDownLatch start, CountDownLatch done, Runnable action) {
        ready.countDown();
        try {
            start.await();
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    private Long persistBooker() {
        User booker = userRepository.save(User.builder()
                .email("booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder().user(booker).paidBalance(0).build());
        return booker.getId();
    }

    private String persistReadyOrder(Long userId, int amount, LocalDateTime createdAt) {
        String orderId = UUID.randomUUID().toString();
        User user = userRepository.findById(userId).orElseThrow();
        paymentOrderRepository.save(PaymentOrder.builder()
                .orderId(orderId).user(user).amount(amount).build());
        jdbcTemplate.update("update payment_orders set created_at = ? where order_id = ?",
                createdAt, orderId);
        return orderId;
    }

    private int balanceOf(Long userId) {
        return bookerAccountRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private TossWebhookPayload webhookPayload(String orderId, String paymentKey) {
        TossWebhookPayload payload = new TossWebhookPayload();
        TossWebhookPayload.Data data = new TossWebhookPayload.Data();
        setField(data, "paymentKey", paymentKey);
        setField(data, "orderId", orderId);
        setField(data, "status", "DONE");
        setField(payload, "data", data);
        return payload;
    }

    private void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
