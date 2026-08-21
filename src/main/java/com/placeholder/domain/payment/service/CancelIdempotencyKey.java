package com.placeholder.domain.payment.service;

/**
 * 토스 취소 요청의 멱등 키 (ADR-019).
 *
 * <p><b>이번 취소액이 아니라 누적 취소액으로</b> 만든다. 이번 취소액을 쓰면 같은 금액의 부분 취소를
 * 두 번 할 때(1만원 주문을 5천원씩) 키가 겹쳐, 토스가 두 번째 요청에 첫 결과를 재생한다 —
 * 우리 장부엔 1만원 취소로 남지만 실제로 돌아간 돈은 5천원인 조용한 손실이 된다.
 * 누적액은 취소가 진행될수록 단조 증가하므로 단계마다 유일하다.
 *
 * <p><b>동기 취소와 역방향 대사가 반드시 같은 키를 만들어야 한다.</b> 대사는 크래시한 시도가 이미
 * 커밋해 둔 누적액을 그대로 재료로 쓰므로, 그 시도가 토스에 도달했었다면 같은 키가 되어 토스가 첫
 * 결과를 재생한다 — 이것이 재시도를 안전하게 만드는 근거다. 두 곳에 키 생성 규칙을 복붙해 두면
 * 한쪽만 바뀌는 순간 그 안전장치가 조용히 사라지므로 한 곳에 모은다.
 */
public final class CancelIdempotencyKey {

    private CancelIdempotencyKey() {
    }

    public static String of(String orderId, int totalCanceledAmount) {
        return "cancel-" + orderId + "-" + totalCanceledAmount;
    }
}
