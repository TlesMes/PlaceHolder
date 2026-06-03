package com.placeholder.domain.seat.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class SeatResponse {
    private Long eventId;
    private List<SeatInfo> seats;

    @Getter
    @Builder
    public static class SeatInfo {
        private Long seatId;
        private String label;
        private int price;
        private String status;
        // HELD 좌석의 홀드 만료 시각. 프론트가 만료된 HELD를 "재점유 가능"으로 인식하는 데 사용.
        // (holder 식별 정보 heldBy는 프라이버시상 노출하지 않음.)
        private LocalDateTime heldUntil;
    }
}
