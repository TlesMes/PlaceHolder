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
}
