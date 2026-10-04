package com.placeholder.global.exception.custom;

/**
 * 토스 승인 요청의 <b>결과를 모른다</b> — 읽기 타임아웃·전송 중 연결 끊김·토스 5xx 등 (ADR-022).
 *
 * <p>{@link PaymentConfirmFailedException}(토스가 거절함)과 구분하는 이유: 이 경우 요청은 토스에
 * 도달해 승인됐을 수 있다. 실패로 처리하면 고객 돈은 빠져나갔는데 포인트는 없는 상태가 굳는다.
 * 이 예외를 받은 쪽은 주문을 {@code IN_PROGRESS}로 두고, 웹훅·대사가 토스에 물어 결정하게 한다.
 */
public class PaymentResultUnknownException extends RuntimeException {
    public PaymentResultUnknownException(String message) {
        super(message);
    }

    public PaymentResultUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
