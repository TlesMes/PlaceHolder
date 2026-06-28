package com.placeholder.domain.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
public class EventCreateRequest {

    @NotBlank(message = "이벤트 제목은 필수입니다")
    private String title;

    @NotBlank(message = "장소는 필수입니다")
    private String venue;

    @NotNull(message = "이벤트 일시는 필수입니다")
    @Future(message = "이벤트 일시는 미래여야 합니다")
    private LocalDateTime eventAt;

    @NotEmpty(message = "최소 1개 이상의 좌석이 필요합니다")
    @Valid
    private List<SeatMetadataDto> seats;

    /** 인기 이벤트만 대기열 활성화 (ADR-013). 미지정 시 false. */
    private boolean queueEnabled;

    @Getter
    @NoArgsConstructor
    public static class SeatMetadataDto {
        @NotBlank(message = "좌석 라벨은 필수입니다")
        private String label;

        @Min(value = 0, message = "가격은 0 이상이어야 합니다")
        private int price;
    }
}
