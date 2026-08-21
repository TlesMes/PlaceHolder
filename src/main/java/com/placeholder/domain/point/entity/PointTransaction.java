package com.placeholder.domain.point.entity;

import com.placeholder.domain.reservation.entity.Reservation;
import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "point_transactions",
    indexes = {
        @Index(name = "idx_pt_user_id", columnList = "user_id"),
        @Index(name = "idx_pt_reservation_id", columnList = "reservation_id"),
        // 제공자 정산 잔액 SUM 커버링 (ADR-021). amount가 인덱스 안에 있어 본체를 읽지 않는다.
        // created_at은 오늘 쓰이지 않는다 — 스냅샷(created_at > ?)과 정산 조회 페이징이 같은
        // 인덱스를 그대로 쓰게 하려는 의도적 배치다. = 조건(user_id, type)이 범위 조건 앞에 온다.
        @Index(name = "idx_pt_settlement_sum", columnList = "user_id, type, created_at, amount")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false)
    private int amount;

    /**
     * 이 거래가 각 재원 계층에서 얼마씩 움직였는지 (ADR-020). {@code CHARGE}·{@code REFUND}는
     * 한 칸만 차고, {@code DEDUCT}만 여러 칸에 걸칠 수 있다(이벤트 3,000 + 무료 2,000처럼).
     *
     * <p>잔액만으로는 "이 포인트가 어디서 왔는지"를 복원할 수 없으므로 이력이 대신 기억한다.
     */
    @Builder.Default
    @Column(name = "bucket_event", nullable = false)
    private int bucketEvent = 0;

    @Builder.Default
    @Column(name = "bucket_free", nullable = false)
    private int bucketFree = 0;

    @Builder.Default
    @Column(name = "bucket_paid", nullable = false)
    private int bucketPaid = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = LocalDateTime.now();
        validateBuckets();
    }

    /**
     * 불변식: {@code amount == bucketEvent + bucketFree + bucketPaid}.
     *
     * <p><b>단, {@code SETTLE}은 제외한다.</b> 이 테이블은 예약자 원장과 제공자 원장을 겸직하는데,
     * {@code SETTLE}은 제공자 앞으로 기록되며, 제공자 정산 잔액은 이 행들의 합으로 파생된다
     * (ADR-021 — 잔액 컬럼은 없다). 제공자에게는 재원 계층이라는 축이 없다 — 예약자가 쿠폰으로 냈든 현금으로 냈든
     * 제공자가 받을 금액은 같기 때문이다. 불변식을 전체에 걸면 {@code amount>0, 버킷합=0}인
     * SETTLE 행이 저장을 거부당하고, DEDUCT와 같은 트랜잭션이라 <b>좌석 확정이 통째로 롤백된다</b>
     * (ADR-020 5번).
     */
    private void validateBuckets() {
        if (type == TransactionType.SETTLE) {
            return;
        }
        int bucketSum = bucketEvent + bucketFree + bucketPaid;
        if (bucketSum != amount) {
            throw new IllegalStateException(
                    "포인트 거래의 재원 배분 합이 금액과 다릅니다: type=" + type
                            + ", amount=" + amount + ", 버킷합=" + bucketSum);
        }
    }

    /**
     * {@code amount}는 타입과 무관하게 <b>양수 크기</b>만 담는다 — 증감 방향은 타입이 정한다
     * (CHARGE/SETTLE은 +, DEDUCT/REFUND는 −).
     */
    public enum TransactionType {
        /** 포인트 충전 (쿠폰 상환 / PG 결제 / 취소 실패 시 복구). */
        CHARGE,
        /** 좌석 예약 확정에 따른 포인트 사용. */
        DEDUCT,
        /** 제공자 정산예정액 적립. */
        SETTLE,
        /**
         * 결제 취소에 따른 포인트 회수 (ADR-019). DEDUCT를 재사용하지 않는 이유는 이력의 의미가
         * 다르기 때문이다 — 사용자가 좌석에 쓴 것(DEDUCT)과 환불로 되돌려진 것(REFUND)이 섞이면
         * 포인트 이력이 사실을 말하지 못한다.
         */
        REFUND
    }
}
