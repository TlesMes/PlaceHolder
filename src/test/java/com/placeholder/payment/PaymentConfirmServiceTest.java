package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.PaymentConfirmResponse;
import com.placeholder.domain.payment.dto.PaymentOrderCreateResponse;
import com.placeholder.domain.payment.dto.TossWebhookPayload;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.domain.payment.service.PaymentWebhookService;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.PaymentAmountMismatchException;
import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PG 연동 백엔드 정합성 검증 (ADR-018). 토스 외부 호출은 {@link TossPaymentClient} 목킹으로 대체하고,
 * 멱등·금액 위변조·승인 실패·웹훅 재조회 로직을 실제 트랜잭션(Testcontainers MySQL)에서 증명한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentConfirmServiceTest extends MySQLIntegrationTest {

    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmService confirmService;
    @Autowired PaymentWebhookService webhookService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;

    @MockitoBean TossPaymentClient tossClient;

    @BeforeEach
    void stubTossSuccess() {
        // 기본: confirm·getPayment 모두 DONE 반환 (실패 케이스는 개별 테스트에서 재정의)
        when(tossClient.confirm(any(), any(), anyInt())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), inv.getArgument(1), "DONE", inv.getArgument(2)));
        when(tossClient.getPayment(any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "DONE", 0));
    }

    @Test
    @DisplayName("주문 생성: orderId 발급 + amount 저장 + READY 상태")
    void createOrder_persistsReadyOrder() {
        Long bookerId = persistBooker(0);

        PaymentOrderCreateResponse res = orderService.createOrder(bookerId, 10_000);

        assertThat(res.getOrderId()).isNotBlank();
        assertThat(res.getAmount()).isEqualTo(10_000);
        PaymentOrder order = paymentOrderRepository.findByOrderId(res.getOrderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(order.getAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("동기 승인 성공: 포인트 적립 + 주문 DONE + CHARGE 1건")
    void confirm_success_credits() {
        Long bookerId = persistBooker(0);
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();

        PaymentConfirmResponse res = confirmService.confirm(orderId, "pk_1", 10_000, bookerId);

        assertThat(res.getChargedAmount()).isEqualTo(10_000);
        assertThat(res.getBalance()).isEqualTo(10_000);
        assertThat(res.getStatus()).isEqualTo("DONE");
        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        assertThat(chargeCount(bookerId)).isEqualTo(1);
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("멱등성: confirm 2회 → 적립 1회, 토스 승인 호출도 1회")
    void confirm_twice_creditsOnce() {
        Long bookerId = persistBooker(0);
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();

        confirmService.confirm(orderId, "pk_1", 10_000, bookerId);
        PaymentConfirmResponse second = confirmService.confirm(orderId, "pk_1", 10_000, bookerId);

        assertThat(second.getBalance()).isEqualTo(10_000);
        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        assertThat(chargeCount(bookerId)).isEqualTo(1);
        // 이미 DONE이면 토스 호출을 건너뛴다 → confirm은 첫 요청 1회만
        verify(tossClient, times(1)).confirm(any(), any(), anyInt());
    }

    @Test
    @DisplayName("금액 위변조: 요청액 ≠ 주문액 → 거부, 적립·상태전이·토스호출 없음")
    void confirm_amountMismatch_rejected() {
        Long bookerId = persistBooker(0);
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();

        assertThatThrownBy(() -> confirmService.confirm(orderId, "pk_1", 10, bookerId))
                .isInstanceOf(PaymentAmountMismatchException.class);

        assertThat(balanceOf(bookerId)).isZero();
        assertThat(chargeCount(bookerId)).isZero();
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.READY);
        verify(tossClient, never()).confirm(any(), any(), anyInt());
    }

    @Test
    @DisplayName("승인 실패: 토스 confirm 실패 → 주문 FAILED, 적립 없음")
    void confirm_tossFails_marksFailed() {
        Long bookerId = persistBooker(0);
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();
        when(tossClient.confirm(any(), any(), anyInt()))
                .thenThrow(new PaymentConfirmFailedException("카드사 거절"));

        assertThatThrownBy(() -> confirmService.confirm(orderId, "pk_1", 10_000, bookerId))
                .isInstanceOf(PaymentConfirmFailedException.class);

        assertThat(balanceOf(bookerId)).isZero();
        assertThat(chargeCount(bookerId)).isZero();
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("웹훅 정상: 재조회 DONE → 멱등 적립 1회")
    void webhook_verifiedDone_credits() {
        Long bookerId = persistBooker(0);
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();

        webhookService.handle(webhookPayload(orderId, "pk_1"));

        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        assertThat(chargeCount(bookerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("웹훅 위조 방어: 재조회 미완료 → 적립 안 함 (페이로드 불신)")
    void webhook_reverifyNotDone_noCredit() {
        Long bookerId = persistBooker(0);
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();
        when(tossClient.getPayment(any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), orderId, "IN_PROGRESS", 10_000));

        webhookService.handle(webhookPayload(orderId, "pk_1"));

        assertThat(balanceOf(bookerId)).isZero();
        assertThat(chargeCount(bookerId)).isZero();
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.READY);
    }

    // --- 헬퍼 ---

    private Long persistBooker(int balance) {
        User booker = userRepository.save(User.builder()
                .email("booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder()
                .user(booker)
                .paidBalance(balance)
                .build());
        return booker.getId();
    }

    private int balanceOf(Long userId) {
        return bookerAccountRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private int chargeCount(Long userId) {
        return pointTransactionRepository.findByTypeAndUserId(TransactionType.CHARGE, userId).size();
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
