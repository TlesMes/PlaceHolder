package com.placeholder.domain.point.entity;

/**
 * 한 번의 차감이 각 재원 계층에서 얼마씩 빠졌는지 (ADR-020).
 *
 * <p>차감은 단일 금액이지만 재원은 여러 계층에 걸칠 수 있다 — 이벤트 3,000이 남은 상태에서
 * 5,000을 쓰면 이벤트 3,000 + 무료 2,000처럼 나뉜다. 잔액만 보면 이 사실이 사라지므로
 * 호출 측이 이력({@link PointTransaction})에 그대로 옮겨 적을 수 있도록 배분 결과를 돌려준다.
 */
public record PointAllocation(int event, int free, int paid) {

    public int total() {
        return event + free + paid;
    }
}
