package com.placeholder.domain.coupon.entity;

import com.placeholder.global.exception.custom.CouponExhaustedException;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 캠페인 쿠폰 — 코드 하나를 다수가 입력해 선착순 maxUses명까지 포인트를 적립받는다.
 * (1회용 기프트카드는 maxUses=1 특수케이스로 통합.)
 * 카운터(usedCount) 증가는 비관적 락(findByCodeForUpdate)을 보유한 상태에서 도메인 메서드 redeem()으로
 * 수행한다. 락이 동시 상환을 직렬화하므로 락 보유 중의 검사·증가는 정합하다 (ADR-010, 좌석 hold와 동일 기조).
 */
@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private int amount;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Builder.Default
    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 소진 여부 (읽기 전용).
     */
    public boolean isExhausted() {
        return usedCount >= maxUses;
    }

    /**
     * 쿠폰 1회 사용 처리 (usedCount 증가). 비관적 락 보유 상태에서만 호출해야 한다.
     * 이미 소진된 쿠폰이면 CouponExhaustedException을 던진다.
     * 상태 변경은 이 도메인 메서드로만 수행한다 (setter 금지).
     */
    public void redeem() {
        if (isExhausted()) {
            throw new CouponExhaustedException("쿠폰이 모두 소진되었습니다");
        }
        this.usedCount++;
    }
}
