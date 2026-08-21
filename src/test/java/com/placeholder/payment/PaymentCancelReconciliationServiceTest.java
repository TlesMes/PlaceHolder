package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentCancelReconciliationService;
import com.placeholder.domain.payment.service.PaymentCancelReconciliationService.CancelReconcileResult;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.PaymentCancelFailedException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 역방향 대사 검증 — "우리는 취소했는데 토스는 모르는" 결제의 복구 (ADR-019).
 *
 * <p>취소 보상 트랜잭션의 ①(포인트 회수 커밋)과 ②(토스 호출) 사이에서 죽으면 포인트만 회수되고
 * 현금은 안 돌아간 상태가 남는다. 이 배치가 그 창을 메우는지, 그리고 <b>메우려다 더 큰 사고를
 * 내지 않는지</b>(이미 환불된 건에 포인트까지 복구 = 돈과 포인트를 둘 다 주는 것)를 확인한다.
 *
 * <p>토스 호출은 목킹하고 상태 전이·포인트는 실제 트랜잭션(Testcontainers MySQL)으로 검증한다.
 * 시각 필드는 도메인 메서드가 now()로 박으므로, 시간 조건을 결정론적으로 만들려면 저장 후
 * JdbcTemplate으로 덮어쓴다(PR #11에서 확립한 방식).
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentCancelReconciliationServiceTest extends MySQLIntegrationTest {

    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    @Autowired PaymentCancelReconciliationService reconciliationService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean TossPaymentClient tossClient;

    /**
     * 대사는 특정 주문이 아니라 <b>구간 전체</b>를 스캔하므로 다른 테스트가 남긴 주문이 결과에 섞인다.
     * 싱글톤 MySQL 컨테이너를 공유하는 구조라 매 테스트 전에 주문 테이블을 비운다.
     */
    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from payment_orders");
    }

    // --- 차액 > 0: 본 크래시 창 ---

    @Test
    @DisplayName("크래시 창 복구: 토스에 취소가 없으면 그 차액만큼 재전송하고 확인 기록을 남긴다")
    void retry_tossHasNoCancel_resendsAndConfirms() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(1), null);
        stubTossState(orderId, 10_000, 0);   // 토스는 취소를 모른다
        stubCancelSuccess();

        CancelReconcileResult result = retryJob();

        assertThat(result.recovered()).isEqualTo(1);
        verify(tossClient).cancel(eq("pk_" + orderId), any(), eq(10_000), any());
        assertThat(cancelConfirmedAtOf(orderId))
                .as("토스 취소가 확인됐으므로 확인 시각이 기록되어야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("멱등 키는 누적 취소액 기준이라 크래시한 시도와 같은 키로 재전송된다")
    void retry_usesCumulativeAmountIdempotencyKey() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(1), null);
        stubTossState(orderId, 10_000, 0);
        stubCancelSuccess();

        retryJob();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(tossClient).cancel(any(), any(), anyInt(), key.capture());
        assertThat(key.getValue())
                .as("동기 취소가 만들었을 키와 같아야 토스가 첫 결과를 재생한다(중복 환불 방지)")
                .isEqualTo("cancel-" + orderId + "-10000");
    }

    // --- 차액 = 0: ②는 성공, ③만 미커밋 ---

    @Test
    @DisplayName("토스가 이미 취소했으면 재호출 없이 확인 기록만 남긴다")
    void retry_tossAlreadyCanceled_stampsOnlyWithoutCallingToss() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(1), null);
        stubTossState(orderId, 10_000, 10_000);   // 토스는 이미 전액 취소됨

        CancelReconcileResult result = retryJob();

        assertThat(result.confirmed()).isEqualTo(1);
        verify(tossClient, never()).cancel(any(), any(), anyInt(), any());
        assertThat(cancelConfirmedAtOf(orderId)).isNotNull();
    }

    // --- 후보 판정: 문서화됐던 조건의 결함 ---

    @Test
    @DisplayName("부분 취소 2회차 크래시도 후보로 잡힌다 — cancel_confirmed_at IS NULL로는 놓친다")
    void retry_secondPartialCancelCrash_isStillDetected() {
        Long bookerId = persistBooker();
        // 1회차 5,000은 확인됨(2시간 전) → 2회차 5,000이 크래시(1시간 전). 누적 10,000.
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(1), hoursAgo(2));

        // 음성 대조군 — 처음 문서화됐던 `cancel_confirmed_at IS NULL` 조건을 실제로 돌려본다.
        // 1회차 확인 시각이 남아 있어 그 조건으로는 이 주문이 후보에서 빠진다(= 영영 복구되지 않는다).
        Integer matchedByOldCondition = jdbcTemplate.queryForObject(
                "select count(*) from payment_orders "
                        + "where canceled_at is not null and cancel_confirmed_at is null "
                        + "and order_id = ?", Integer.class, orderId);
        assertThat(matchedByOldCondition)
                .as("IS NULL 조건은 부분 취소 2회차 크래시를 잡지 못한다 — 그래서 두 시각을 비교한다")
                .isZero();

        stubTossState(orderId, 10_000, 5_000);   // 토스는 1회차 5,000만 알고 있다
        stubCancelSuccess();

        CancelReconcileResult result = retryJob();

        assertThat(result.recovered()).isEqualTo(1);
        verify(tossClient).cancel(any(), any(), eq(5_000), any());   // 차액만 재전송
    }

    @Test
    @DisplayName("정상 확정된 취소는 후보가 아니다 — 토스를 조회조차 하지 않는다")
    void retry_confirmedCancel_isNotACandidate() {
        Long bookerId = persistBooker();
        // 확인 시각이 취소 시각보다 뒤 = 정상 완료
        persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(2), hoursAgo(1));

        CancelReconcileResult result = retryJob();

        assertThat(result.scanned()).isZero();
        verify(tossClient, never()).findByOrderId(any());
    }

    @Test
    @DisplayName("진행 중인 취소(min-age 이내)는 건드리지 않는다 — 중복 호출 방지")
    void retry_inFlightCancel_isExcludedByMinAge() {
        Long bookerId = persistBooker();
        persistCanceledOrder(bookerId, 10_000, 10_000, LocalDateTime.now().minusMinutes(1), null);

        CancelReconcileResult result = retryJob();

        assertThat(result.scanned()).isZero();
        verify(tossClient, never()).findByOrderId(any());
    }

    // --- 권한 분리: 재시도 잡은 되돌리지 못한다 ---

    @Test
    @DisplayName("재시도 잡은 토스가 실패해도 포인트를 되돌리지 않는다 (revert 권한 없음)")
    void retry_tossFails_doesNotRevert() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(1), null);
        stubTossState(orderId, 10_000, 0);
        stubCancelFailure();

        CancelReconcileResult result = retryJob();

        assertThat(result.reverted()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(balanceOf(bookerId)).as("일시적 장애가 곧바로 환불 포기로 이어지면 안 된다").isZero();
        assertThat(canceledAmountOf(orderId)).isEqualTo(10_000);
        assertThat(cancelConfirmedAtOf(orderId)).isNull();
    }

    // --- 포기 잡 ---

    @Test
    @DisplayName("포기 잡: 오래도록 실패하면 포인트를 복구하고 주문을 되돌린다")
    void giveUp_tossKeepsFailing_revertsPoints() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(30), null);
        stubTossState(orderId, 10_000, 0);
        stubCancelFailure();

        CancelReconcileResult result = giveUpJob();

        assertThat(result.reverted()).isEqualTo(1);
        assertThat(balanceOf(bookerId)).as("현금 환불을 포기했으므로 포인트로 되돌려준다").isEqualTo(10_000);
        assertThat(chargeCount(bookerId)).as("복구도 실제 잔액 이동이므로 이력에 남아야 한다").isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.DONE);
        assertThat(canceledAmountOf(orderId)).isZero();
    }

    @Test
    @DisplayName("포기 잡이라도 토스가 이미 취소했으면 되돌리지 않는다 — 돈과 포인트를 둘 다 주면 안 된다")
    void giveUp_tossAlreadyCanceled_confirmsInsteadOfReverting() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(30), null);
        stubTossState(orderId, 10_000, 10_000);   // 재시도 잡이 죽어 있는 사이 실제로는 취소됐다

        CancelReconcileResult result = giveUpJob();

        assertThat(result.reverted()).isZero();
        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(balanceOf(bookerId)).as("현금이 돌아갔으므로 포인트를 주면 이중 지급이다").isZero();
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("포기 잡: 부분 취소는 차액만 되돌리고 나머지는 확인 처리한다")
    void giveUp_partialCancel_revertsOnlyTheUnconfirmedDelta() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(30), hoursAgo(31));
        stubTossState(orderId, 10_000, 5_000);   // 토스는 5,000만 취소했다
        stubCancelFailure();

        CancelReconcileResult result = giveUpJob();

        assertThat(result.reverted()).isEqualTo(1);
        assertThat(balanceOf(bookerId)).as("토스가 취소하지 않은 5,000만 복구").isEqualTo(5_000);
        assertThat(canceledAmountOf(orderId)).isEqualTo(5_000);
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(cancelConfirmedAtOf(orderId))
                .as("잔여 취소분은 토스가 확인해 준 부분이므로 스탬프를 찍어 후보에서 배출한다")
                .isAfter(canceledAtOf(orderId));
    }

    // --- 자동 판단하지 않는 경우 ---

    @Test
    @DisplayName("토스가 장부보다 많이 취소했으면 아무것도 하지 않는다 (포인트 추가 회수는 자동 결정 대상 아님)")
    void reconcile_tossCanceledMore_skipsForManualReview() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 5_000, hoursAgo(30), null);
        stubTossState(orderId, 10_000, 10_000);   // 외부 개입 등으로 토스가 더 취소

        CancelReconcileResult result = giveUpJob();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.reverted()).isZero();
        assertThat(balanceOf(bookerId)).isZero();
        assertThat(canceledAmountOf(orderId)).isEqualTo(5_000);
        assertThat(cancelConfirmedAtOf(orderId)).isNull();
    }

    // --- 멱등 ---

    @Test
    @DisplayName("두 번 돌려도 토스 취소는 한 번만 나간다")
    void retry_runTwice_cancelsOnce() {
        Long bookerId = persistBooker();
        String orderId = persistCanceledOrder(bookerId, 10_000, 10_000, hoursAgo(1), null);
        stubTossState(orderId, 10_000, 0);
        stubCancelSuccess();

        retryJob();
        CancelReconcileResult second = retryJob();

        assertThat(second.scanned()).as("확인 기록이 남아 후보에서 빠진다").isZero();
        verify(tossClient, times(1)).cancel(any(), any(), anyInt(), any());
    }

    // --- 헬퍼 ---

    /** 재시도 잡과 동일 파라미터 (스케줄러 기본값: 5분 ~ 24시간, revert 권한 없음) */
    private CancelReconcileResult retryJob() {
        LocalDateTime now = LocalDateTime.now();
        return reconciliationService.reconcile(now.minusHours(24), now.minusMinutes(5), false);
    }

    /** 포기 잡과 동일 파라미터 (24시간 이전, revert 권한 있음) */
    private CancelReconcileResult giveUpJob() {
        return reconciliationService.reconcile(EPOCH, LocalDateTime.now().minusHours(24), true);
    }

    /** 토스가 보고할 상태를 설정한다. balanceAmount(취소가능 잔액)로 누적 취소액을 표현한다. */
    private void stubTossState(String orderId, int totalAmount, int tossCanceledAmount) {
        String status = tossCanceledAmount == 0 ? "DONE"
                : tossCanceledAmount == totalAmount ? "CANCELED" : "PARTIAL_CANCELED";
        when(tossClient.findByOrderId(orderId)).thenReturn(Optional.of(new TossPaymentResult(
                "pk_" + orderId, orderId, status, totalAmount, totalAmount - tossCanceledAmount)));
    }

    private void stubCancelSuccess() {
        when(tossClient.cancel(any(), any(), anyInt(), any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "CANCELED", 10_000, 0));
    }

    private void stubCancelFailure() {
        when(tossClient.cancel(any(), any(), anyInt(), any()))
                .thenThrow(new PaymentCancelFailedException("토스 취소 호출 실패"));
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

    /**
     * 취소 기록까지 마친 주문을 만든다 — 포인트는 이미 회수된 상태를 가정한다(잔액 0에서 시작).
     *
     * <p>시각은 도메인 메서드가 now()로 박으므로 저장 후 덮어쓴다. {@code INTERVAL ? HOUR} 같은
     * 파라미터 바인딩 대신 Java에서 계산한 값을 직접 바인딩하고 <b>갱신 행 수를 단정</b>한다 —
     * PR #26에서 backdate가 빗나갔는데도 "거부"를 기대하는 테스트라 통과해버린 전례가 있다.
     */
    private String persistCanceledOrder(Long userId, int amount, int canceledAmount,
                                        LocalDateTime canceledAt, LocalDateTime cancelConfirmedAt) {
        String orderId = UUID.randomUUID().toString();
        User user = userRepository.findById(userId).orElseThrow();

        PaymentOrder order = PaymentOrder.builder()
                .orderId(orderId).user(user).amount(amount).build();
        order.markDone("pk_" + orderId);
        order.markCanceled(canceledAmount);
        paymentOrderRepository.save(order);

        int updated = jdbcTemplate.update(
                "update payment_orders set canceled_at = ?, cancel_confirmed_at = ? where order_id = ?",
                Timestamp.valueOf(canceledAt),
                cancelConfirmedAt == null ? null : Timestamp.valueOf(cancelConfirmedAt),
                orderId);
        assertThat(updated).as("backdate 대상 주문이 갱신되어야 한다").isEqualTo(1);
        return orderId;
    }

    private static LocalDateTime hoursAgo(int hours) {
        return LocalDateTime.now().minusHours(hours);
    }

    private PaymentOrder orderOf(String orderId) {
        return paymentOrderRepository.findByOrderId(orderId).orElseThrow();
    }

    private PaymentStatus statusOf(String orderId) {
        return orderOf(orderId).getStatus();
    }

    private int canceledAmountOf(String orderId) {
        return orderOf(orderId).getCanceledAmount();
    }

    private LocalDateTime cancelConfirmedAtOf(String orderId) {
        return orderOf(orderId).getCancelConfirmedAt();
    }

    private LocalDateTime canceledAtOf(String orderId) {
        return orderOf(orderId).getCanceledAt();
    }

    private int balanceOf(Long userId) {
        return bookerAccountRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private int chargeCount(Long userId) {
        return pointTransactionRepository.findByTypeAndUserId(TransactionType.CHARGE, userId).size();
    }
}
