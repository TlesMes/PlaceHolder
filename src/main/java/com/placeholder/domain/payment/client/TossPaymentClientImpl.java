package com.placeholder.domain.payment.client;

import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

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
            @Value("${toss.secret-key:}") String secretKey) {

        String basic = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        this.restClient = RestClient.builder()
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
