package com.placeholder.global.exception.custom;

/**
 * 대기열이 활성화된 이벤트에서 입장 토큰 없이 hold를 시도했을 때 (ADR-013, E-1 3단계).
 * 클라이언트는 대기열에 진입(POST /api/queue/{eventId}/enter)해 차례를 기다려야 한다.
 */
public class QueueAdmissionRequiredException extends RuntimeException {
    public QueueAdmissionRequiredException(String message) {
        super(message);
    }
}
