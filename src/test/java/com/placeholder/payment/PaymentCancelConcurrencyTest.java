package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.TossWebhookPayload;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentCancelService;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.domain.payment.service.PaymentWebhookService;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 취소의 동시성 정합성 (ADR-019).
 *
 * <p>취소에서 중복은 결제의 중복보다 나쁘다 — 이중 적립은 우리가 손해를 보고 끝나지만,
 * <b>이중 취소는 사용자에게 돈을 두 번 돌려주면서 포인트는 한 번만 회수</b>하는 순손실이다.
 * 방어선은 주문 행 비관적 락 + "취소 기록을 외부 호출보다 먼저" 순서다: 먼저 락을 얻은 요청이
 * 상태를 PARTIAL_CANCELED/CANCELED로 바꾸고 커밋하므로, 뒤따르는 요청은 잔여액을 다시 계산하지 못한다.
 *
 * <p>C-4(좌석 경합)·쿠폰 exactly-K·PR #22(confirm×웹훅)와 같은 종류의 증명이다.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class PaymentCancelConcurrencyTest extends MySQLIntegrationTest {

    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmService confirmService;
    @Autowired PaymentCancelService cancelService;
    @Autowired PaymentWebhookService webhookService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;

    @MockitoBean TossPaymentClient tossClient;

    @BeforeEach
    void stubToss() {
        when(tossClient.confirm(any(), any(), anyInt())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), inv.getArgument(1), "DONE", inv.getArgument(2)));
        when(tossClient.getPayment(any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "DONE", 10_000));
        when(tossClient.cancel(any(), any(), anyInt(), any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "CANCELED", inv.getArgument(2)));
    }

    @RepeatedTest(5)
    @DisplayName("취소 2건 동시 도착 → 환불 정확히 1회 (토스 취소 호출도 1회)")
    void concurrentCancels_refundExactlyOnce() throws InterruptedException {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);

        AtomicInteger succeeded = new AtomicInteger();
        runConcurrently(
                () -> { cancelService.cancel(orderId, bookerId, "요청 A"); succeeded.incrementAndGet(); },
                () -> { cancelService.cancel(orderId, bookerId, "요청 B"); succeeded.incrementAndGet(); });

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(balanceOf(bookerId)).isZero();
        assertThat(countOf(bookerId, TransactionType.REFUND)).isEqualTo(1);
        // 핵심: 토스에 취소가 두 번 나가지 않았다 (두 번 나가면 돈을 두 번 돌려준다)
        verify(tossClient, times(1)).cancel(any(), any(), anyInt(), any());

        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(order.getCanceledAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("취소 후 지연 도착한 적립 경로(웹훅·대사) → 재적립 없음 (환불한 포인트가 되살아나지 않는다)")
    void settleAfterCancel_doesNotRecredit() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);
        cancelService.cancel(orderId, bookerId, "고객 요청");

        // 취소 뒤에 도착한 웹훅 — 대사가 같은 주문을 settle 하는 경우도 동일한 코어를 탄다
        webhookService.handle(webhookPayload(orderId, "pk_" + orderId));

        assertThat(balanceOf(bookerId)).isZero();
        assertThat(countOf(bookerId, TransactionType.CHARGE)).isEqualTo(1);
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELED);
    }

    @RepeatedTest(5)
    @DisplayName("취소 × 웹훅 동시 도착 → 적립 1회·환불 1회 (도착 순서와 무관하게 잔액 0)")
    void cancelAndWebhook_race_staysConsistent() throws InterruptedException {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);

        runConcurrently(
                () -> cancelService.cancel(orderId, bookerId, "고객 요청"),
                () -> webhookService.handle(webhookPayload(orderId, "pk_" + orderId)));

        // 웹훅이 취소 전에 오든 후에 오든 이미 승인된 주문이라 no-op이어야 한다
        assertThat(countOf(bookerId, TransactionType.CHARGE)).isEqualTo(1);
        assertThat(countOf(bookerId, TransactionType.REFUND)).isEqualTo(1);
        assertThat(balanceOf(bookerId)).isZero();
        verify(tossClient, atMost(1)).cancel(any(), any(), anyInt(), any());
    }

    // --- 헬퍼 ---

    /** 두 작업을 같은 순간에 출발시킨다. 예외는 경합의 정상 결과이므로 삼키고 최종 상태로 판정한다. */
    private void runConcurrently(Runnable first, Runnable second) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        for (Runnable task : new Runnable[]{first, second}) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (Exception ignored) {
                    // 락 경합에서 진 쪽의 거부는 기대된 동작
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }

    private Long persistBooker() {
        User booker = userRepository.save(User.builder()
                .email("cancel-race-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder().user(booker).paidBalance(0).build());
        return booker.getId();
    }

    private String chargedOrder(Long bookerId, int amount) {
        String orderId = orderService.createOrder(bookerId, amount).getOrderId();
        confirmService.confirm(orderId, "pk_" + orderId, amount, bookerId);
        return orderId;
    }

    private int balanceOf(Long userId) {
        return bookerAccountRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private int countOf(Long userId, TransactionType type) {
        return pointTransactionRepository.findByTypeAndUserId(type, userId).size();
    }

    private TossWebhookPayload webhookPayload(String orderId, String paymentKey) {
        TossWebhookPayload payload = new TossWebhookPayload();
        TossWebhookPayload.Data data = new TossWebhookPayload.Data();
        setField(data, "paymentKey", paymentKey);
        setField(data, "orderId", orderId);
        setField(data, "status", "DONE");
        setField(payload, "eventType", "PAYMENT_STATUS_CHANGED");
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
