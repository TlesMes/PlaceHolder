package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentReconciliationService;
import com.placeholder.domain.payment.service.PaymentReconciliationService.ReconcileResult;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 결제 대사 검증 (ADR-018 삼중화의 세 번째 층).
 *
 * <p>동기 승인과 웹훅이 둘 다 실패해 READY로 남은 주문을 토스와 대조해 보정하는지, 그리고
 * <b>보정 잡이 정상 결제를 성급히 종결시키지 않는지</b>를 확인한다. 토스 호출은 목킹하고
 * 상태 전이·적립은 실제 트랜잭션(Testcontainers MySQL)으로 검증한다.
 *
 * <p>{@code createdAt}은 {@code @PrePersist}로 now() 고정이라, 시간 조건을 결정론적으로 만들려면
 * 저장 후 JdbcTemplate으로 덮어써야 한다(PR #11에서 확립한 방식).
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentReconciliationServiceTest extends MySQLIntegrationTest {

    @Autowired PaymentReconciliationService reconciliationService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean TossPaymentClient tossClient;

    /**
     * 대사는 특정 주문이 아니라 <b>구간 전체</b>를 스캔하므로, 다른 테스트가 남긴 READY 주문이
     * 스캔 결과(scanned/skipped)에 그대로 섞인다. 싱글톤 MySQL 컨테이너를 공유하는 구조라
     * 결과를 결정론적으로 만들려면 매 테스트 전에 주문 테이블을 비워야 한다.
     * (point_transactions는 PaymentOrder를 참조하지 않으므로 함께 지울 필요가 없고,
     *  잔액 검증은 테스트마다 새로 만든 booker 기준이라 영향받지 않는다.)
     */
    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("delete from payment_orders");
    }

    // --- 분기: 토스가 DONE ---

    @Test
    @DisplayName("누락 결제 발견: 토스 DONE → 적립 보정 + 주문 DONE 전이")
    void reconcile_tossDone_creditsMissedPayment() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(1));
        stubToss(orderId, "DONE", 10_000);

        ReconcileResult result = reconcileRecent();

        assertThat(result.credited()).isEqualTo(1);
        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        assertThat(chargeCount(bookerId)).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("금액 위변조 방어: 토스 DONE이어도 금액이 다르면 보정하지 않는다")
    void reconcile_amountMismatch_doesNotCredit() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(1));
        stubToss(orderId, "DONE", 999); // 주문액과 불일치

        ReconcileResult result = reconcileRecent();

        assertThat(result.credited()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(balanceOf(bookerId)).isZero();
        assertThat(chargeCount(bookerId)).isZero();
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.READY); // 종결시키지 않음
    }

    // --- 분기: 토스가 실패·진행중 ---

    @Test
    @DisplayName("토스 CANCELED → FAILED 전이, 적립 없음")
    void reconcile_tossCanceled_marksFailed() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(1));
        stubToss(orderId, "CANCELED", 10_000);

        ReconcileResult result = reconcileRecent();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.FAILED);
        assertThat(balanceOf(bookerId)).isZero();
    }

    @Test
    @DisplayName("토스 IN_PROGRESS → 건드리지 않고 다음 주기로")
    void reconcile_tossInProgress_skips() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(1));
        stubToss(orderId, "IN_PROGRESS", 10_000);

        ReconcileResult result = reconcileRecent();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.READY);
    }

    // --- 분기: 토스에 기록 없음(404) — 만료 권한 분리의 핵심 ---

    @Test
    @DisplayName("★ 보정 잡은 404를 만료시키지 않는다 — 성급한 종결이 정상 결제를 거부하게 만들기 때문")
    void reconcile_notFound_withoutExpirePermission_leavesReady() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(1));
        when(tossClient.findByOrderId(orderId)).thenReturn(Optional.empty());

        ReconcileResult result = reconcileRecent(); // allowExpire = false

        assertThat(result.expired()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("만료 잡은 404를 EXPIRED로 종결한다 (고아 주문 청소)")
    void expiryJob_notFound_marksExpired() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(48));
        when(tossClient.findByOrderId(orderId)).thenReturn(Optional.empty());

        ReconcileResult result = expireOrphans(); // allowExpire = true

        assertThat(result.expired()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    @DisplayName("만료 잡도 토스를 재조회한다: 24h 지난 READY가 실은 DONE이면 뒤늦게라도 적립")
    void expiryJob_stillCreditsIfTossSaysDone() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(48));
        stubToss(orderId, "DONE", 10_000);

        ReconcileResult result = expireOrphans();

        assertThat(result.credited()).isEqualTo(1);
        assertThat(result.expired()).isZero();
        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.DONE);
    }

    // --- 스캔 범위 ---

    @Test
    @DisplayName("유예시간 이내(방금 생성)의 주문은 후보에서 제외 — 토스 조회조차 하지 않는다")
    void reconcile_tooRecent_isNotScanned() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, LocalDateTime.now()); // 방금

        ReconcileResult result = reconcileRecent();

        assertThat(result.scanned()).isZero();
        verify(tossClient, never()).findByOrderId(any());
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("상한(2h) 초과 주문은 보정 잡 후보에서 제외 — 새벽 배치 소관")
    void reconcile_tooOld_isLeftToNightBatch() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(5));

        ReconcileResult result = reconcileRecent();

        assertThat(result.scanned()).isZero();
        verify(tossClient, never()).findByOrderId(any());
        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.READY);
    }

    // --- 멱등 ---

    @Test
    @DisplayName("멱등: 이미 DONE인 주문은 후보에 없고, 대사를 두 번 돌려도 적립 1회")
    void reconcile_twice_creditsOnce() {
        Long bookerId = persistBooker();
        String orderId = persistReadyOrder(bookerId, 10_000, hoursAgo(1));
        stubToss(orderId, "DONE", 10_000);

        reconcileRecent();
        ReconcileResult second = reconcileRecent();

        assertThat(second.scanned()).isZero(); // DONE 전이로 후보에서 빠짐
        assertThat(balanceOf(bookerId)).isEqualTo(10_000);
        assertThat(chargeCount(bookerId)).isEqualTo(1);
    }

    // --- 헬퍼 ---

    /** 보정 잡과 동일 파라미터 (스케줄러 기본값: 5분 ~ 2시간, 만료 권한 없음) */
    private ReconcileResult reconcileRecent() {
        LocalDateTime now = LocalDateTime.now();
        return reconciliationService.reconcile(now.minusHours(2), now.minusMinutes(5), false);
    }

    /** 만료 잡과 동일 파라미터 (24시간 이전, 만료 권한 있음) */
    private ReconcileResult expireOrphans() {
        return reconciliationService.reconcile(
                LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.now().minusHours(24), true);
    }

    private void stubToss(String orderId, String status, int totalAmount) {
        when(tossClient.findByOrderId(orderId)).thenReturn(
                Optional.of(new TossPaymentResult("pk_" + orderId, orderId, status, totalAmount)));
    }

    private Long persistBooker() {
        User booker = userRepository.save(User.builder()
                .email("booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder().user(booker).balance(0).build());
        return booker.getId();
    }

    /** createdAt은 @PrePersist로 고정되므로 저장 후 JdbcTemplate으로 덮어써 시간 조건을 결정론적으로 만든다. */
    private String persistReadyOrder(Long userId, int amount, LocalDateTime createdAt) {
        String orderId = UUID.randomUUID().toString();
        User user = userRepository.findById(userId).orElseThrow();
        paymentOrderRepository.save(PaymentOrder.builder()
                .orderId(orderId)
                .user(user)
                .amount(amount)
                .build());
        jdbcTemplate.update("update payment_orders set created_at = ? where order_id = ?",
                createdAt, orderId);
        return orderId;
    }

    private static LocalDateTime hoursAgo(int hours) {
        return LocalDateTime.now().minusHours(hours);
    }

    private PaymentStatus statusOf(String orderId) {
        return paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus();
    }

    private int balanceOf(Long userId) {
        return bookerAccountRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private int chargeCount(Long userId) {
        return pointTransactionRepository.findByTypeAndUserId(TransactionType.CHARGE, userId).size();
    }
}
