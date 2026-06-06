package com.placeholder.domain.coupon.entity;

import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 쿠폰 유저당 1회 상환 기록. (coupon_id, user_id) 유니크 제약으로 동일 유저의 중복 상환을 막는다.
 * 동시 상환 시 DB가 유니크 제약으로 하나만 통과시키므로 락 없이 중복을 방어한다.
 */
@Entity
@Table(
    name = "coupon_redemptions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_redemption_coupon_user",
        columnNames = {"coupon_id", "user_id"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CouponRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "redeemed_at", nullable = false, updatable = false)
    private LocalDateTime redeemedAt;

    @PrePersist
    private void prePersist() {
        this.redeemedAt = LocalDateTime.now();
    }
}
