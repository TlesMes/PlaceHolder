package com.placeholder.domain.provider.entity;

import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "provider_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ProviderAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // 정산 잔액 컬럼은 없다. 잔액은 SETTLE 원장의 합으로 파생시킨다 (ADR-021).
    //
    // 같은 사실을 원장(INSERT)과 잔액(UPDATE) 두 곳에 저장하면 갱신 방식이 달라 진실이 갈라진다 —
    // 실제로 갈라졌고(PR #28: 이력 10행 / 잔액 5,000), 락으로 막았더니 이번엔 같은 제공자의
    // 판매가 이 행 하나에서 직렬화됐다(초당 약 85건 천장, PR #31 측정).
    //
    // 남은 id·user는 마커다: 정산 조회가 "제공자 계정 없음" 검증에 쓰고, 향후 PAYOUT·정산 계좌
    // 정보가 붙을 자리다.
}
