package com.placeholder.domain.seat.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 좌석 hold 반환 응답. 이탈 정리는 멱등이라 요청이 실제로 반환을 수행했는지(released)를
 * 상태와 함께 알려, 프론트가 no-op(타인 홀드·이미 AVAILABLE·CONFIRMED)와 구분할 수 있게 한다.
 */
@Getter
@Builder
public class SeatReleaseResponse {
    private Long seatId;
    private String status;
    private boolean released;
}
