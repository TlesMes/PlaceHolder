package com.placeholder.domain.seat.repository;

/**
 * 대기열 게이트 판정에 필요한 좌석→이벤트 최소 정보 (E-1 3단계).
 * hold 진입 시 좌석 행을 잠그기 전에 비잠금으로 이벤트의 대기열 활성화 여부를 확인하기 위함.
 */
public interface SeatGateProjection {
    Long getEventId();
    boolean isQueueEnabled();
}
