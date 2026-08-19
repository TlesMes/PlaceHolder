package com.placeholder.domain.point.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 보유 포인트 — 합산 + 재원 계층별 분해 (ADR-020).
 */
@Getter
@Builder
public class PointBalanceResponse {

    /** 사용자에게 보이는 "보유 포인트". 세 계층의 합. */
    private int total;

    /** 기간제 포인트(판촉 쿠폰)분 (소멸 미구현 — 현재 항상 0). */
    private int event;

    /** 무기한 쿠폰 상환분. */
    private int free;

    /** 현금 결제 충전분. */
    private int paid;

    /**
     * 환불 가능 금액. 지금은 {@link #paid}와 같지만 <b>별도 필드로 내보낸다</b> —
     * 부분 환불 제한 같은 정책이 붙으면 두 값이 갈라지므로, 프론트가 {@code paid}를
     * 환불 가능액으로 읽는 결합을 미리 끊어 둔다.
     */
    private int refundable;
}
