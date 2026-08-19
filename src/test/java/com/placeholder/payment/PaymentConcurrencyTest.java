package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.TossWebhookPayload;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.domain.payment.service.PaymentWebhookService;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * confirm(동기)과 webhook(보조)이 같은 orderId로 동시에 적립을 시도해도 정확히 1회만 적립됨을 증명 (ADR-018).
 *
 * <p>두 경로는 {@link com.placeholder.domain.payment.service.PaymentSettlementService#settle}로 수렴하고,
 * 주문 행 비관적 락이 이를 직렬화한다 → 이중 적립(lost update)이 발생하지 않는다. 좌석 hold/confirm 경합
 * (C-4)·쿠폰 상환 exactly-K와 동일한 종류의 정합성 증명이다.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class PaymentConcurrencyTest extends MySQLIntegrationTest {

    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmService confirmService;
    @Autowired PaymentWebhookService webhookService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;

    @MockitoBean TossPaymentClient tossClient;

    @RepeatedTest(5)
    @DisplayName("confirm × webhook 동시 도착 → 적립 정확히 1회 (이중 적립 없음)")
    void confirmAndWebhook_race_creditsExactlyOnce() throws InterruptedException {
        when(tossClient.confirm(any(), any(), anyInt())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), inv.getArgument(1), "DONE", inv.getArgument(2)));
        when(tossClient.getPayment(any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "DONE", 0));

        Long bookerId = persistBooker();
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        executor.submit(() -> race(ready, start, done,
                () -> confirmService.confirm(orderId, "pk_1", 10_000, bookerId)));
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
        bookerAccountRepository.save(BookerAccount.builder()
                .user(booker)
                .paidBalance(0)
                .build());
        return booker.getId();
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
