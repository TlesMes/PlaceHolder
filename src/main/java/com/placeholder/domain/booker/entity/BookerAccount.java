package com.placeholder.domain.booker.entity;

import com.placeholder.global.exception.custom.InsufficientPointException;
import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "booker_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BookerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Builder.Default
    @Column(nullable = false)
    private int balance = 0;

    public void deduct(int amount) {
        if (this.balance < amount) {
            throw new InsufficientPointException("포인트 잔액이 부족합니다");
        }
        this.balance -= amount;
    }

    /**
     * 포인트 적립. 충전 경로(쿠폰/관리자/PG)와 무관한 코어 — 진입 경로가 무엇이든 이 메서드로 수렴한다.
     * 상태 변경은 이 도메인 메서드로만 수행한다 (setter 금지).
     */
    public void charge(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 양수여야 합니다");
        }
        this.balance += amount;
    }
}
