package com.placeholder.domain.queue.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 대기열 진입/상태 조회 응답.
 *
 * @param position 1-based 대기 순번. 대기열에 없으면 null
 * @param waiting  전체 대기 인원
 * @param admitted 입장 토큰 보유 여부 (true면 hold 진입 가능)
 */
@Getter
@Builder
public class QueueStatusResponse {
    private Long eventId;
    private Long position;
    private long waiting;
    private boolean admitted;
}
