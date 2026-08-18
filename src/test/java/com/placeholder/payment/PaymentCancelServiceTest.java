package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.PaymentCancelResponse;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentCancelService;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.PaymentCancelFailedException;
import com.placeholder.global.exception.custom.PaymentCancelNotAllowedException;
import com.placeholder.global.exception.custom.PaymentOrderNotFoundException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 결제 취소·환불 검증 (ADR-019).
 *
 * <p>결제(적립)와 달리 취소는 <b>보상 트랜잭션</b>이다 — 순서를 뒤집어(포인트 선회수 → 토스 취소)
 * 되돌릴 수 있는 쪽을 먼저 움직이고, 외부 호출이 실패하면 되돌린다. 이 클래스가 증명하는 것은
 * ① 미사용분만 환불되는가 ② 실패 시 포인트가 정확히 복구되는가 ③ 기한·상태 가드가 작동하는가다.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class PaymentCancelServiceTest extends MySQLIntegrationTest {

    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmService confirmService;
    @Autowired PaymentCancelService cancelService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean TossPaymentClient tossClient;

    @BeforeEach
    void stubToss() {
        when(tossClient.confirm(any(), any(), anyInt())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), inv.getArgument(1), "DONE", inv.getArgument(2)));
        when(tossClient.cancel(any(), any(), anyInt(), any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "CANCELED", inv.getArgument(2)));
    }

    @Test
    @DisplayName("전액 취소: 미사용 잔액 전부 환불 → CANCELED + 포인트 0 + REFUND 1건")
    void cancel_full_refundsAll() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);

        PaymentCancelResponse res = cancelService.cancel(orderId, bookerId, "고객 요청");

        assertThat(res.getCanceledAmount()).isEqualTo(10_000);
        assertThat(res.getTotalCanceledAmount()).isEqualTo(10_000);
        assertThat(res.getBalance()).isZero();
        assertThat(res.getStatus()).isEqualTo("CANCELED");

        assertThat(balanceOf(bookerId)).isZero();
        assertThat(countOf(bookerId, TransactionType.REFUND)).isEqualTo(1);

        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(order.getCanceledAmount()).isEqualTo(10_000);
        // 토스 취소가 확인된 뒤에만 기록된다 — "돈이 실제로 돌아갔다"의 표시
        assertThat(order.getCancelConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("부분 환불: 잔액 < 충전액이면 잔액만큼만 취소 → PARTIAL_CANCELED (쓴 포인트는 회수 불가)")
    void cancel_partial_refundsOnlyRemainingBalance() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);
        spend(bookerId, 4_000);   // 좌석 예약 등으로 이미 사용

        PaymentCancelResponse res = cancelService.cancel(orderId, bookerId, "고객 요청");

        assertThat(res.getCanceledAmount()).isEqualTo(6_000);
        assertThat(res.getStatus()).isEqualTo("PARTIAL_CANCELED");
        assertThat(balanceOf(bookerId)).isZero();
        // 토스에도 잔액만큼만 취소 요청이 나가야 한다(1만원 전액 취소 요청이면 우리가 손해)
        verify(tossClient).cancel(any(), any(), eq(6_000), any());

        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(order.getCanceledAmount()).isEqualTo(6_000);
        assertThat(order.remainingAmount()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("멱등: 전액 취소 후 재취소 → 거부, 회수는 1회뿐 (토스 취소 호출도 1회)")
    void cancel_twice_refundsOnce() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);

        cancelService.cancel(orderId, bookerId, "고객 요청");
        assertThatThrownBy(() -> cancelService.cancel(orderId, bookerId, "고객 요청"))
                .isInstanceOf(PaymentCancelNotAllowedException.class);

        assertThat(balanceOf(bookerId)).isZero();
        assertThat(countOf(bookerId, TransactionType.REFUND)).isEqualTo(1);
        verify(tossClient, times(1)).cancel(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("보상 트랜잭션: 토스 취소 실패 → 포인트 원상복구 + 주문 DONE 복귀 (순손실 없음)")
    void cancel_tossFails_restoresPoints() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);
        when(tossClient.cancel(any(), any(), anyInt(), any()))
                .thenThrow(new PaymentCancelFailedException("토스 취소 호출 실패"));

        assertThatThrownBy(() -> cancelService.cancel(orderId, bookerId, "고객 요청"))
                .isInstanceOf(PaymentCancelFailedException.class);

        // 돈이 안 돌아갔으므로 포인트도 그대로여야 한다
        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(order.getCanceledAmount()).isZero();
        assertThat(order.getCancelConfirmedAt()).isNull();
        // 이력은 지우지 않고 반대 거래로 남긴다 — 잔액이 실제로 두 번 움직였으므로
        assertThat(countOf(bookerId, TransactionType.REFUND)).isEqualTo(1);
        assertThat(countOf(bookerId, TransactionType.CHARGE)).isEqualTo(2);
    }

    @Test
    @DisplayName("취소 기한: 승인 7일 초과 주문 → 거부, 토스 호출 없음")
    void cancel_afterPeriod_rejected() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);
        backdateApprovedAt(orderId, 8);

        assertThatThrownBy(() -> cancelService.cancel(orderId, bookerId, "고객 요청"))
                .isInstanceOf(PaymentCancelNotAllowedException.class)
                .hasMessageContaining("기간");

        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        verify(tossClient, never()).cancel(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("잔액 0: 충전분을 전부 써버렸으면 환불 불가 (음수 잔액을 만들지 않는다)")
    void cancel_noBalance_rejected() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);
        spend(bookerId, 10_000);

        assertThatThrownBy(() -> cancelService.cancel(orderId, bookerId, "고객 요청"))
                .isInstanceOf(PaymentCancelNotAllowedException.class)
                .hasMessageContaining("환불 가능한 금액");

        assertThat(balanceOf(bookerId)).isZero();
        verify(tossClient, never()).cancel(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("미승인 주문(READY): 취소 대상이 아님 → 거부")
    void cancel_readyOrder_rejected() {
        Long bookerId = persistBooker();
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();

        assertThatThrownBy(() -> cancelService.cancel(orderId, bookerId, "고객 요청"))
                .isInstanceOf(PaymentCancelNotAllowedException.class);

        verify(tossClient, never()).cancel(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("남의 주문: 존재를 숨기고 not found (본인 주문만 취소 가능)")
    void cancel_othersOrder_notFound() {
        Long owner = persistBooker();
        Long stranger = persistBooker();
        String orderId = chargedOrder(owner, 10_000);

        assertThatThrownBy(() -> cancelService.cancel(orderId, stranger, "고객 요청"))
                .isInstanceOf(PaymentOrderNotFoundException.class);

        assertThat(balanceOf(owner)).isEqualTo(10_000);
        verify(tossClient, never()).cancel(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("부분 취소 2회: 잔여액 범위에서 이어서 취소되고 누적액이 합산된다")
    void cancel_partialTwice_accumulates() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);
        spend(bookerId, 6_000);

        // 1차: 잔액 4천 취소
        cancelService.cancel(orderId, bookerId, "1차");
        // 사용자가 다시 4천을 충전해 잔액이 생긴 상황 (쿠폰 등) — 주문 잔여액 6천이 상한
        chargeBalance(bookerId, 4_000);
        PaymentCancelResponse second = cancelService.cancel(orderId, bookerId, "2차");

        assertThat(second.getCanceledAmount()).isEqualTo(4_000);
        assertThat(second.getTotalCanceledAmount()).isEqualTo(8_000);
        assertThat(second.getStatus()).isEqualTo("PARTIAL_CANCELED");
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().remainingAmount())
                .isEqualTo(2_000);
    }

    @Test
    @DisplayName("취소 기한 경계: 마감 1시간 전은 가능, 1시간 후는 거부 (기준은 승인 + 7×24시간)")
    void cancel_periodBoundary() {
        // 마감 직전 — 아직 취소할 수 있어야 한다
        Long inTime = persistBooker();
        String orderA = chargedOrder(inTime, 10_000);
        backdateApprovedAtHours(orderA, 7 * 24 - 1);
        assertThat(cancelService.cancel(orderA, inTime, "마감 1시간 전").getStatus()).isEqualTo("CANCELED");

        // 마감 직후 — 거부되어야 한다. 기한은 달력 날짜가 아니라 승인 시각 + 168시간으로 센다
        // (정확히 168시간 되는 그 순간의 동작은 knife-edge라 단정하지 않는다)
        Long tooLate = persistBooker();
        String orderB = chargedOrder(tooLate, 10_000);
        backdateApprovedAtHours(orderB, 7 * 24 + 1);
        assertThatThrownBy(() -> cancelService.cancel(orderB, tooLate, "마감 1시간 후"))
                .isInstanceOf(PaymentCancelNotAllowedException.class)
                .hasMessageContaining("기간");
        assertThat(balanceOf(tooLate)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("토스가 200을 주면서 취소 반영 안 했으면(status=DONE) 실패로 보고 포인트 복구")
    void cancel_tossReturnsNotCanceled_compensates() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);
        // 예외는 안 나지만 상태가 취소가 아니다 — 확인하지 않으면 취소 안 된 결제를 취소로 기록한다
        when(tossClient.cancel(any(), any(), anyInt(), any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "DONE", inv.getArgument(2)));

        assertThatThrownBy(() -> cancelService.cancel(orderId, bookerId, "고객 요청"))
                .isInstanceOf(PaymentCancelFailedException.class);

        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(order.getCanceledAmount()).isZero();
        assertThat(order.getCancelConfirmedAt()).isNull();
    }

    @Test
    @DisplayName("취소된 주문에 confirm 재호출: 재적립 없고 응답 상태도 CANCELED (DONE이라 거짓말하지 않는다)")
    void confirmAfterCancel_reportsRealStatus() {
        Long bookerId = persistBooker();
        String orderId = chargedOrder(bookerId, 10_000);
        cancelService.cancel(orderId, bookerId, "고객 요청");

        var res = confirmService.confirm(orderId, "pk_" + orderId, 10_000, bookerId);

        assertThat(res.getStatus()).isEqualTo("CANCELED");
        assertThat(balanceOf(bookerId)).isZero();
        assertThat(countOf(bookerId, TransactionType.CHARGE)).isEqualTo(1);
    }

    // --- 헬퍼 ---

    private Long persistBooker() {
        User booker = userRepository.save(User.builder()
                .email("cancel-booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder().user(booker).balance(0).build());
        return booker.getId();
    }

    /** 주문 생성 + 동기 승인까지 마친 DONE 주문의 orderId. */
    private String chargedOrder(Long bookerId, int amount) {
        String orderId = orderService.createOrder(bookerId, amount).getOrderId();
        confirmService.confirm(orderId, "pk_" + orderId, amount, bookerId);
        return orderId;
    }

    /** 좌석 예약 등으로 포인트를 소진한 상황 재현. */
    private void spend(Long userId, int amount) {
        BookerAccount account = bookerAccountRepository.findByUserId(userId).orElseThrow();
        account.deduct(amount);
        bookerAccountRepository.save(account);
    }

    private void chargeBalance(Long userId, int amount) {
        BookerAccount account = bookerAccountRepository.findByUserId(userId).orElseThrow();
        account.charge(amount);
        bookerAccountRepository.save(account);
    }

    /**
     * 승인 시각을 과거로 되돌린다. {@code approvedAt}은 {@code markDone()}이 {@code now()}로 고정하므로
     * 엔티티 경유로는 과거를 만들 수 없다 — 조회 API 테스트(PR #11)에서 확립한 방식대로 SQL로 덮어쓴다.
     */
    private void backdateApprovedAt(String orderId, int days) {
        backdateApprovedAtHours(orderId, days * 24);
    }

    /**
     * 승인 시각을 과거로 되돌린다. {@code approvedAt}은 {@code markDone()}이 {@code now()}로 고정하므로
     * 엔티티 경유로는 과거를 만들 수 없다 — 조회 API 테스트(PR #11)에서 확립한 방식대로 SQL로 덮어쓴다.
     *
     * <p>시각은 Java에서 계산해 바인딩한다. {@code INTERVAL ? HOUR}처럼 파라미터를 SQL 식 안에 넣으면
     * 드라이버가 어떻게 보내느냐에 따라 해석이 달라질 수 있어 경계 검증에는 부적합하다.
     */
    private void backdateApprovedAtHours(String orderId, int hours) {
        int updated = jdbcTemplate.update(
                "update payment_orders set approved_at = ? where order_id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(hours)), orderId);
        assertThat(updated).as("backdate 대상 주문이 갱신되어야 한다").isEqualTo(1);
    }

    private int balanceOf(Long userId) {
        return bookerAccountRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private int countOf(Long userId, TransactionType type) {
        List<?> rows = pointTransactionRepository.findByTypeAndUserId(type, userId);
        return rows.size();
    }
}
