package com.placeholder.domain.payment.client;

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
}
