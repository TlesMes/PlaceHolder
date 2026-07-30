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
 * <p>상태는 {@code READY → DONE} (승인·적립 완료) 또는 {@code READY → FAILED} (승인 실패)로만
 * 전이한다. 종결 상태(DONE/FAILED)에서 재전이는 거부한다. 상태 변경은 도메인 메서드로만 수행한다
 * (setter 금지). confirm(동기)과 webhook(보조)이 같은 orderId로 동시에 도착해도, 비관적 락
 * ({@code findByOrderIdForUpdate}) 보유자만 READY→DONE 전이를 하므로 포인트는 정확히 1회 적립된다.
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

    public enum PaymentStatus {
        READY, DONE, FAILED
    }
}
