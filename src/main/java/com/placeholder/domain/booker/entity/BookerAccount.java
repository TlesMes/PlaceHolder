package com.placeholder.domain.booker.entity;

import com.placeholder.global.exception.custom.InsufficientPointException;
import com.placeholder.domain.point.entity.PointAllocation;
import com.placeholder.domain.point.entity.PointBucket;
import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

/**
 * 예약자 포인트 계정. 잔액은 <b>재원 계층별로</b> 나뉘어 저장된다 (ADR-020).
 *
 * <p>합계 컬럼을 따로 두지 않는 이유는 유도 방향이 한쪽으로만 성립하기 때문이다 — 버킷에서
 * 총합은 언제나 구할 수 있지만, 총합 12,000에서 (3,000 / 2,000 / 7,000)은 복원할 수 없다.
 * 둘 다 저장하면 어느 한쪽만 갱신하는 코드가 언젠가 생기고 그때 진실이 갈라진다.
 * 따라서 <b>버킷이 진실이고 {@link #getBalance()}는 파생</b>이다.
 */
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

    /** 기간제 이벤트 쿠폰분. 환불 불가. (만료 미구현 — 현재 항상 0) */
    @Builder.Default
    @Column(name = "event_balance", nullable = false)
    private int eventBalance = 0;

    /** 무기한 쿠폰 상환분. 환불 불가. */
    @Builder.Default
    @Column(name = "free_balance", nullable = false)
    private int freeBalance = 0;

    /** 현금 결제 충전분. <b>유일한 환불 재원.</b> */
    @Builder.Default
    @Column(name = "paid_balance", nullable = false)
    private int paidBalance = 0;

    /**
     * 총 잔액 — 세 버킷의 합(파생값, 컬럼 아님). 사용자에게 보이는 "보유 포인트"이자
     * 잔액 부족 판정의 기준이다.
     */
    public int getBalance() {
        return eventBalance + freeBalance + paidBalance;
    }

    /**
     * 환불에 쓸 수 있는 잔액. 쿠폰으로 받은 포인트를 현금으로 돌려줄 수는 없으므로 유료분뿐이다.
     * 이 메서드가 곧 "쿠폰 → 현금 환전" 구멍을 막는 지점이다 (ADR-020).
     */
    public int refundableBalance() {
        return paidBalance;
    }

    /**
     * 포인트 차감. <b>소모 순서는 {@link PointBucket} 선언 순서</b>(EVENT → FREE → PAID)를 따른다.
     *
     * <p>잔액 부족 판정은 계층별이 아니라 <b>총합 기준</b>이다 — 사용자 입장에서 보유 포인트는
     * 하나이고, 어느 계층에서 빠지는지는 내부 사정이기 때문이다.
     *
     * <p><b>반드시 계정 행 락을 쥔 상태에서 호출해야 한다.</b> 세 값을 읽어 배분을 계산하고 다시
     * 세 값을 쓰는 read-modify-write이므로, 계산이 락 밖에서 이뤄지면 동시 차감 둘이 같은
     * 이벤트 잔액을 각각 소진했다고 계산한다 (ADR-020 4번).
     *
     * @return 각 계층에서 실제로 빠진 금액 — 호출 측이 이력에 기록한다
     */
    public PointAllocation deduct(int amount) {
        if (getBalance() < amount) {
            throw new InsufficientPointException("포인트 잔액이 부족합니다");
        }
        int fromEvent = Math.min(amount, eventBalance);
        int fromFree = Math.min(amount - fromEvent, freeBalance);
        int fromPaid = amount - fromEvent - fromFree;

        this.eventBalance -= fromEvent;
        this.freeBalance -= fromFree;
        this.paidBalance -= fromPaid;

        return new PointAllocation(fromEvent, fromFree, fromPaid);
    }

    /**
     * 지정한 계층에서만 차감한다. 환불 전용 — 환불액은 유료 잔액을 넘을 수 없으므로 호출 측이
     * {@link #refundableBalance()}로 상한을 건 뒤 사용한다.
     */
    public void deductFrom(int amount, PointBucket bucket) {
        if (amount <= 0) {
            throw new IllegalArgumentException("차감 금액은 양수여야 합니다");
        }
        switch (bucket) {
            case EVENT -> {
                requireEnough(eventBalance, amount);
                this.eventBalance -= amount;
            }
            case FREE -> {
                requireEnough(freeBalance, amount);
                this.freeBalance -= amount;
            }
            case PAID -> {
                requireEnough(paidBalance, amount);
                this.paidBalance -= amount;
            }
        }
    }

    /**
     * 포인트 적립. 충전 경로(쿠폰/관리자/PG)와 무관한 코어 — 진입 경로가 무엇이든 이 메서드로 수렴하되,
     * <b>어느 재원으로 들어오는지는 호출 측이 명시</b>해야 한다. 경로가 재원을 결정하기 때문이다
     * (쿠폰 상환 → FREE, 결제 승인 → PAID).
     * 상태 변경은 이 도메인 메서드로만 수행한다 (setter 금지).
     */
    public void charge(int amount, PointBucket bucket) {
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 양수여야 합니다");
        }
        switch (bucket) {
            case EVENT -> this.eventBalance += amount;
            case FREE -> this.freeBalance += amount;
            case PAID -> this.paidBalance += amount;
        }
    }

    private void requireEnough(int bucketBalance, int amount) {
        if (bucketBalance < amount) {
            throw new InsufficientPointException("포인트 잔액이 부족합니다");
        }
    }
}
