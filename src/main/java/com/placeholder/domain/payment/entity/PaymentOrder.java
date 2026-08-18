package com.placeholder.domain.payment.entity;

import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 결제 주문 — "현금 → 포인트" 충전 1건의 상태머신 (ADR-018).
 *
 * <p>주문 생성 시점에 서버가 {@code amount}를 확정 저장한다. 이후 confirm/웹훅이 들고 오는 금액은
 * 이 저장값과 대조해 위변조를 막는다(클라이언트가 보낸 금액을 신뢰하지 않는다).
 *
 * <p>상태는 {@code READY → DONE} (승인·적립 완료) 또는 {@code READY → FAILED} (승인 실패)로 전이하고,
 * 확정된 주문은 취소로 {@code DONE → PARTIAL_CANCELED → CANCELED}까지 이어질 수 있다 (ADR-019).
 * 그 외 재전이는 거부한다. 상태 변경은 도메인 메서드로만 수행한다 (setter 금지). confirm(동기)과
 * webhook(보조)이 같은 orderId로 동시에 도착해도, 비관적 락({@code findByOrderIdForUpdate}) 보유자만
 * READY→DONE 전이를 하므로 포인트는 정확히 1회 적립된다.
 */
@Entity
@Table(
    name = "payment_orders",
    indexes = {
        @Index(name = "idx_payment_order_id", columnList = "order_id", unique = true)
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 서버가 생성해 토스에 전달하는 주문 식별자 (UUID). 멱등·조회의 키. */
    @Column(name = "order_id", nullable = false, unique = true, updatable = false)
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 주문 시점에 서버가 확정한 결제 금액(원) = 충전 포인트(1:1). 위변조 검증 기준. */
    @Column(nullable = false, updatable = false)
    private int amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.READY;

    /** 토스 승인 키. 확정(DONE) 시에만 기록된다. */
    @Column(name = "payment_key")
    private String paymentKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /**
     * 누적 취소 금액(원). 부분 취소를 여러 번 할 수 있으므로 단일 플래그가 아니라 누계로 관리한다.
     * {@code canceledAmount == amount}이면 전액 취소(CANCELED), 그 미만이면 PARTIAL_CANCELED.
     */
    @Builder.Default
    @Column(name = "canceled_amount", nullable = false)
    private int canceledAmount = 0;

    /** 마지막 취소 <b>기록</b> 시각 — 포인트를 회수한 시점(토스 취소 성공 여부와 무관). */
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    /**
     * 토스 취소가 <b>실제로 확인된</b> 시각. {@code canceledAt}과 분리한 이유가 이 필드의 존재 이유다 —
     * 우리 DB는 취소로 기록됐는데 토스 취소 호출 결과를 확인하기 전에 서버가 죽으면
     * "포인트만 회수되고 돈은 안 돌아간" 상태가 된다. 그 창을 나중에 식별하려면
     * "기록했다"와 "상대도 확인했다"를 구분해 남겨야 한다(역방향 대사의 후보 조건).
     */
    @Column(name = "cancel_confirmed_at")
    private LocalDateTime cancelConfirmedAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean isDone() {
        return status == PaymentStatus.DONE;
    }

    public boolean isReady() {
        return status == PaymentStatus.READY;
    }

    /**
     * 이미 적립이 완료된 이력이 있는가 — 멱등 판정용.
     *
     * <p>{@link #isDone()}만으로는 부족하다: 취소된 주문(CANCELED/PARTIAL_CANCELED)도 <b>한 번은
     * 적립됐던</b> 주문이다. 취소 후 뒤늦게 도착한 웹훅이 {@code isDone()==false}를 보고 재적립을
     * 시도하면 이미 환불한 포인트를 되돌려주게 된다. 적립 여부는 "DONE인가"가 아니라
     * "READY를 벗어나 승인된 적이 있는가"로 판정해야 한다.
     */
    public boolean isSettled() {
        return status == PaymentStatus.DONE
                || status == PaymentStatus.PARTIAL_CANCELED
                || status == PaymentStatus.CANCELED;
    }

    /**
     * 승인·적립 완료 처리. READY 상태에서만 호출 가능(종결 상태 재전이 거부).
     * 비관적 락 보유 상태에서만 호출해야 한다. 상태 변경은 이 도메인 메서드로만 수행한다(setter 금지).
     */
    public void markDone(String paymentKey) {
        if (status != PaymentStatus.READY) {
            throw new IllegalStateException("READY 상태의 주문만 확정할 수 있습니다: status=" + status);
        }
        this.status = PaymentStatus.DONE;
        this.paymentKey = paymentKey;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 승인 실패 처리. READY 상태에서만 전이(이미 확정된 주문은 실패로 뒤집지 않는다).
     */
    public void markFailed() {
        if (status != PaymentStatus.READY) {
            throw new IllegalStateException("READY 상태의 주문만 실패 처리할 수 있습니다: status=" + status);
        }
        this.status = PaymentStatus.FAILED;
    }

    /**
     * 고아 주문 만료 처리 — 결제창을 띄우지 않고 이탈해 토스에도 기록이 없는 주문의 종결 (대사 배치).
     *
     * <p>{@code FAILED}(토스가 거절)와 구분한다. 이쪽은 "애초에 결제 시도 자체가 없었다"는 뜻이라
     * 원인 분석·통계에서 섞이면 안 된다. 좌석 hold 만료(ADR-009)와 같은 성격의 청소다.
     *
     * <p>⚠️ 성급한 만료는 사고를 만든다 — 주문 생성 직후엔 토스도 아직 그 orderId를 모르므로(404),
     * 이때 만료시키면 곧이어 결제를 마친 사용자의 confirm이 거부된다(돈은 나갔는데 포인트 없음).
     * 그래서 이 전이는 충분히 오래된 주문만 다루는 새벽 배치에만 허용한다.
     */
    public void markExpired() {
        if (status != PaymentStatus.READY) {
            throw new IllegalStateException("READY 상태의 주문만 만료 처리할 수 있습니다: status=" + status);
        }
        this.status = PaymentStatus.EXPIRED;
    }

    /** 취소 가능한 상태인가 — 승인 완료됐고 아직 전액 취소되지 않았다. */
    public boolean isCancelable() {
        return status == PaymentStatus.DONE || status == PaymentStatus.PARTIAL_CANCELED;
    }

    /** 아직 취소되지 않고 남아 있는 결제 금액(원). 이 주문에서 환불 가능한 상한. */
    public int remainingAmount() {
        return amount - canceledAmount;
    }

    /** 취소 기한(승인 시각 + {@code days}) 이내인가. 전자상거래법 청약철회 기간 반영 (ADR-019). */
    public boolean isWithinCancelPeriod(LocalDateTime now, int days) {
        return approvedAt != null && !now.isAfter(approvedAt.plusDays(days));
    }

    /**
     * 취소 기록 — <b>포인트 회수와 같은 트랜잭션에서</b> 먼저 호출한다 (ADR-019 보상 순서).
     *
     * <p>토스 취소 성공을 기다렸다가 상태를 바꾸면, 그 사이에 들어온 두 번째 취소 요청이 잔여액을
     * 다시 계산해 <b>토스에 중복 취소를 날린다.</b> 상태 전이 자체가 동시 취소의 방어선이므로
     * 외부 호출보다 먼저 기록하고, 실패하면 {@link #revertCancel(int)}로 되돌린다.
     */
    public void markCanceled(int cancelAmount) {
        if (!isCancelable()) {
            throw new IllegalStateException("취소할 수 없는 주문 상태입니다: status=" + status);
        }
        if (cancelAmount <= 0 || cancelAmount > remainingAmount()) {
            throw new IllegalArgumentException(
                    "취소 금액이 잘못되었습니다: cancelAmount=" + cancelAmount + ", remaining=" + remainingAmount());
        }
        this.canceledAmount += cancelAmount;
        this.canceledAt = LocalDateTime.now();
        this.status = (this.canceledAmount == this.amount)
                ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED;
    }

    /**
     * 취소 기록 롤백 (보상) — 토스 취소 호출이 실패했을 때 {@link #markCanceled} 이전 상태로 되돌린다.
     * 포인트 복구와 같은 트랜잭션에서 호출한다. 취소가 0으로 돌아가면 DONE으로 복귀한다.
     */
    public void revertCancel(int cancelAmount) {
        if (cancelAmount <= 0 || cancelAmount > this.canceledAmount) {
            throw new IllegalArgumentException(
                    "되돌릴 취소 금액이 잘못되었습니다: cancelAmount=" + cancelAmount
                            + ", canceledAmount=" + this.canceledAmount);
        }
        this.canceledAmount -= cancelAmount;
        this.status = (this.canceledAmount == 0)
                ? PaymentStatus.DONE : PaymentStatus.PARTIAL_CANCELED;
        if (this.canceledAmount == 0) {
            this.canceledAt = null;
        }
    }

    /** 토스 취소가 실제로 확인됨. 여기까지 와야 "돈이 돌아갔다"고 말할 수 있다. */
    public void confirmCancel() {
        this.cancelConfirmedAt = LocalDateTime.now();
    }

    public enum PaymentStatus {
        READY, DONE, FAILED, EXPIRED, PARTIAL_CANCELED, CANCELED
    }
}
