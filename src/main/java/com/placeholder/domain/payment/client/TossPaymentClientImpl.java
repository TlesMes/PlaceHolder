package com.placeholder.domain.payment.client;

import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * 토스페이먼츠 실제 REST 호출 구현 (Spring Boot 내장 {@link RestClient}, 별도 의존성 없음).
 *
 * <p>인증은 시크릿 키를 사용한 HTTP Basic Auth다. 토스 규격상 사용자명에 시크릿 키, 비밀번호는 빈 값이며
 * 콜론(:)까지 포함해 Base64 인코딩한다 → {@code Authorization: Basic base64(secretKey + ":")}.
 *
 * <p>키가 비어 있으면(로컬·CI에 미발급) 실제 호출은 인증 실패하지만, 통합 테스트는 {@link TossPaymentClient}를
 * 목킹해 이 빈을 대체하므로 무관하다. 실 샌드박스 키 확보 후 env로 주입하면 그대로 동작한다(ADR-018).
 */
@Slf4j
@Component
public class TossPaymentClientImpl implements TossPaymentClient {

    private final RestClient restClient;

    public TossPaymentClientImpl(
            @Value("${toss.api-base-url:https://api.tosspayments.com}") String apiBaseUrl,
            @Value("${toss.secret-key:}") String secretKey,
            @Value("${toss.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${toss.read-timeout-ms:5000}") long readTimeoutMs) {

        String basic = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        // 타임아웃은 선택 설정이 아니라 필수다. 이 호출은 트랜잭션 밖이라 DB 커넥션은 잡지 않지만
        // (ADR-018), 응답이 오지 않으면 요청 스레드는 계속 붙잡힌다 — 토스가 매달리는 동안 스레드가
        // 쌓이면 좌석 hold 같은 무관한 경로까지 함께 굶는다. 느린 하류 의존성의 장애 전파는 대기열
        // (E-1)이 막아주지 못하는 종류라(대기열은 유입 셰이핑) 여기서 상한을 건다.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public TossPaymentResult confirm(String paymentKey, String orderId, int amount) {
        try {
            TossPaymentResponse res = restClient.post()
                    .uri("/v1/payments/confirm")
                    .body(Map.of(
                            "paymentKey", paymentKey,
                            "orderId", orderId,
                            "amount", amount))
                    .retrieve()
                    .body(TossPaymentResponse.class);
            return toResult(res);
        } catch (RestClientException e) {
            throw new PaymentConfirmFailedException("토스 결제 승인 호출 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public TossPaymentResult getPayment(String paymentKey) {
        TossPaymentResponse res = restClient.get()
                .uri("/v1/payments/{paymentKey}", paymentKey)
                .retrieve()
                .body(TossPaymentResponse.class);
        return toResult(res);
    }

    @Override
    public Optional<TossPaymentResult> findByOrderId(String orderId) {
        // 404 = "토스에 이 주문 기록이 없다"는 정상적인 상태 구분(결제창 미오픈)이라 예외로 올리지 않는다.
        // 기본 동작은 4xx를 예외로 던지므로 onStatus로 가로채 빈 본문을 반환하게 한다.
        TossPaymentResponse res = restClient.get()
                .uri("/v1/payments/orders/{orderId}", orderId)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> { })
                .body(TossPaymentResponse.class);

        return Optional.ofNullable(res).map(this::toResult);
    }

    private TossPaymentResult toResult(TossPaymentResponse res) {
        if (res == null) {
            throw new PaymentConfirmFailedException("토스 응답 본문이 비어 있습니다");
        }
        return new TossPaymentResult(res.paymentKey(), res.orderId(), res.status(), res.totalAmount());
    }

    /** 토스 응답 중 우리가 쓰는 필드만 매핑 (그 외 필드는 무시). */
    private record TossPaymentResponse(String paymentKey, String orderId, String status, int totalAmount) {
    }
}
