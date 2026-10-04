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
 * <p>상태는 {@code READY → IN_PROGRESS → DONE} (승인·적립 완료) 또는 {@code → FAILED} (승인 실패)로
 * 전이하고, 확정된 주문은 취소로 {@code DONE → PARTIAL_CANCELED → CANCELED}까지 이어질 수 있다 (ADR-019).
 * {@code IN_PROGRESS}는 "토스에 승인을 요청했고 결과는 아직 모른다"이다 (ADR-022).
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

    /** 아직 승인 결과가 확정되지 않은 주문 — 승인 전({@code READY}) 또는 결과 대기({@code IN_PROGRESS}). */
    public boolean isPending() {
        return status == PaymentStatus.READY || status == PaymentStatus.IN_PROGRESS;
    }

    public boolean isInProgress() {
        return status == PaymentStatus.IN_PROGRESS;
    }

    /**
     * 승인 요청 시작 — <b>토스 호출 전에</b> 커밋한다 (ADR-022).
     *
     * <p>이 전이가 없으면 "아직 요청 안 함"과 "요청했는데 결과 모름"이 둘 다 READY라 구분되지 않는다.
     * 그 결과 ① 첫 요청이 진행 중일 때 들어온 두 번째 요청(새로고침)이 READY를 보고 토스를 또 호출하고,
     * ② 응답을 못 받은 결제를 FAILED로 뭉갤 수밖에 없었다(토스는 승인했는데 포인트는 없는 상태).
     * 비관적 락을 쥔 채로 READY에서만 전이하므로 동시 요청 중 하나만 토스를 호출한다.
     */
    public void markInProgress() {
        if (status != PaymentStatus.READY) {
            throw new IllegalStateException("READY 상태의 주문만 승인을 시작할 수 있습니다: status=" + status);
        }
        this.status = PaymentStatus.IN_PROGRESS;
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
     * 승인·적립 완료 처리. 결과 미확정({@link #isPending()}) 상태에서만 호출 가능(종결 상태 재전이 거부).
     *
     * <p>{@code READY}에서도 허용하는 이유: 승인 요청이 토스엔 도달했지만 우리가 {@code IN_PROGRESS}를
     * 기록하기 전 경로(웹훅·대사가 먼저 도착)도 있다. 토스가 DONE이라고 확인해 준 이상 적립이 맞다.
     * 비관적 락 보유 상태에서만 호출해야 한다. 상태 변경은 이 도메인 메서드로만 수행한다(setter 금지).
     */
    public void markDone(String paymentKey) {
        if (!isPending()) {
            throw new IllegalStateException("결과 미확정 주문만 확정할 수 있습니다: status=" + status);
        }
        this.status = PaymentStatus.DONE;
        this.paymentKey = paymentKey;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 승인 실패 처리. 결과 미확정 상태에서만 전이(이미 확정된 주문은 실패로 뒤집지 않는다).
     *
     * <p>⚠️ <b>"토스가 거절했다"가 확인됐을 때만</b> 호출한다. 타임아웃·전송 실패처럼 결과를 모르는
     * 경우에 부르면 토스가 실제로 승인한 결제가 실패로 굳는다 — 그런 주문은 {@code IN_PROGRESS}로
     * 두고 웹훅·대사가 토스에 물어 결정한다 (ADR-022).
     */
    public void markFailed() {
        if (!isPending()) {
            throw new IllegalStateException("결과 미확정 주문만 실패 처리할 수 있습니다: status=" + status);
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
     *
     * <p>{@code IN_PROGRESS}도 받는다 — 하루가 지나도 토스에 기록이 없다면 승인 요청이 도달하지
     * 않은 것이고, 돈도 움직이지 않았다.
     */
    public void markExpired() {
        if (!isPending()) {
            throw new IllegalStateException("결과 미확정 주문만 만료 처리할 수 있습니다: status=" + status);
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

    /** 취소 기한(승인 시각 + {@code days}) 이내인가. 기한은 카드 취소의 실질 상한 (ADR-019 1-1). */
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

    /**
     * 사용자에게 보여줄 <b>환불 진행 상태</b> — 두 시각에서 파생한다(별도 컬럼 없음).
     *
     * <p>이 구분이 없으면 화면이 거짓말을 한다. 취소 ①이 커밋되는 순간 {@code REFUND} 거래가
     * 찍히고 잔액도 줄어들지만, ②(토스 호출)가 아직이면 <b>현금은 돌아가지 않았다.</b>
     * 그런데 포인트 이력만 보는 사용자에겐 "환불됨"으로 보인다.
     *
     * <p>{@link RefundStatus#PENDING}과 {@link RefundStatus#COMPLETED}는 성격이 다르다 —
     * 전자는 "요청이 아직 상대에게 가지 않았다", 후자는 "우리 할 일은 끝났고 카드사 반영 대기".
     *
     * <p>⚠️ 판정 조건은 {@code PaymentOrderRepository.findUnconfirmedCancels}의 JPQL과
     * <b>같은 뜻이어야 한다.</b> 한쪽만 바뀌면 "화면엔 처리 중인데 배치는 안 줍는" 주문이 생긴다.
     */
    public RefundStatus refundStatus() {
        if (canceledAt == null) {
            return RefundStatus.NONE;
        }
        boolean confirmed = cancelConfirmedAt != null && cancelConfirmedAt.isAfter(canceledAt);
        return confirmed ? RefundStatus.COMPLETED : RefundStatus.PENDING;
    }

    public enum PaymentStatus {
        READY,
        /** 토스에 승인을 요청했고 결과는 아직 모른다 (ADR-022). 웹훅·대사가 토스에 물어 확정한다. */
        IN_PROGRESS,
        DONE, FAILED, EXPIRED, PARTIAL_CANCELED, CANCELED
    }

    /** 환불 진행 상태 (파생값 — 저장하지 않는다). */
    public enum RefundStatus {
        /** 취소 이력 없음. */
        NONE,
        /** 우리 장부는 취소인데 토스 확인 전 — 현금은 아직 돌아가지 않았다. */
        PENDING,
        /** 토스 취소까지 확인됨 (카드사 반영은 영업일 소요). */
        COMPLETED
    }
}
