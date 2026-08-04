package com.placeholder.domain.payment.client;

import java.util.Optional;

/**
 * 토스페이먼츠 외부 API 호출 추상화 (ADR-018).
 *
 * <p>외부 I/O를 인터페이스 뒤로 숨겨, ① 서비스 로직(멱등·금액검증·트랜잭션 경계)을 실제 네트워크
 * 호출과 분리해 테스트에서 목킹하고, ② 실제 샌드박스 키가 확보되면 {@code TossPaymentClientImpl}
 * (RestClient) 실호출로 바꿔 끼울 수 있게 한다. 서비스는 이 인터페이스에만 의존한다(DIP).
 */
public interface TossPaymentClient {

    /**
     * 결제 승인(확정). 서버 전용 시크릿 키로 호출한다.
     * 승인에 실패하면 {@link com.placeholder.global.exception.custom.PaymentConfirmFailedException}을 던진다.
     */
    TossPaymentResult confirm(String paymentKey, String orderId, int amount);

    /**
     * 결제 단건 조회. 웹훅 수신 시 페이로드를 신뢰하지 않고 실제 상태를 재확인하는 데 쓴다(위조 웹훅 방어).
     */
    TossPaymentResult getPayment(String paymentKey);

    /**
     * <b>orderId로</b> 결제 조회 — 대사(reconciliation)의 축이 되는 메서드.
     *
     * <p>누락된 주문은 {@code status=READY}라 {@code paymentKey}가 아직 null이다(승인 성공 시에만 기록).
     * 따라서 {@link #getPayment(String)}으로는 조회 자체가 불가능하고, 우리가 발급해 항상 보유한
     * orderId로 "이 주문이 토스에선 어떻게 됐나"를 물어야 한다.
     *
     * <p>토스에 해당 주문 기록이 없으면(404) 예외가 아니라 {@link Optional#empty()}를 반환한다 —
     * "아직 결제창을 띄우지 않았다"는 정상적인 상태 구분이지 오류가 아니기 때문이다.
     */
    Optional<TossPaymentResult> findByOrderId(String orderId);
}
