package com.placeholder.domain.queue.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 대기열 진입/상태 조회 응답.
 *
 * @param position        1-based 대기 순번. 대기열에 없으면 null
 * @param waiting         전체 대기 인원
 * @param admitted        입장 토큰 보유 여부 (true면 hold 진입 가능)
 * @param nextPollDelayMs 다음 status 폴링까지 권장 대기(ms). 서버가 (앞 인원 / rate) 예상 대기시간
 *                        기반으로 계산 — 클라이언트는 이 값으로 폴링 주기를 동적 조절한다.
 */
@Getter
@Builder
public class QueueStatusResponse {
    private Long eventId;
    private Long position;
    private long waiting;
    private boolean admitted;
    private long nextPollDelayMs;
}
