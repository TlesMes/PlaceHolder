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
        @Index(name = "idx_pt_reservation_id", columnList = "reservation_id")
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = LocalDateTime.now();
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
