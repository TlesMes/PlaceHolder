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

    public enum PaymentStatus {
        READY, DONE, FAILED, EXPIRED
    }
}
