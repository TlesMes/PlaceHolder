package com.placeholder.domain.payment.client;

import com.placeholder.global.exception.custom.PaymentCancelFailedException;
import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
 *
 * <p><b>전송 실패는 예외 타입을 가리지 않고 도메인 예외로 번역한다.</b> {@code RestClientException}만
 * 잡으면 새는 경로가 있다 — 읽기 타임아웃이 요청 전송 단계와 겹치면 JDK HttpClient가
 * {@code CompletableFuture.cancel()}로 중단하는데, 이때 나오는 {@code CancellationException}은
 * {@code RestClientException}이 아니어서 그대로 빠져나간다. 그러면 호출 측(confirm)의 실패 처리가
 * 건너뛰어져 승인 실패 주문이 READY로 남고 사용자에겐 500이 나간다.
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
        } catch (PaymentConfirmFailedException e) {
            throw e;   // 이미 우리 예외(빈 본문 등) — 이중 포장하지 않는다
        } catch (RuntimeException e) {
            throw new PaymentConfirmFailedException("토스 결제 승인 호출 실패: " + e, e);
        }
    }

    @Override
    public TossPaymentResult cancel(String paymentKey, String cancelReason, int cancelAmount,
                                    String idempotencyKey) {
        try {
            TossPaymentResponse res = restClient.post()
                    .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of(
                            "cancelReason", cancelReason,
                            "cancelAmount", cancelAmount))
                    .retrieve()
                    .body(TossPaymentResponse.class);
            return toResult(res);
        } catch (RuntimeException e) {
            // 취소 실패는 삼키지 않는다 — 호출 측이 이 예외를 받아 선회수한 포인트를 복구해야 한다(ADR-019).
            throw new PaymentCancelFailedException("토스 결제 취소 호출 실패: " + e, e);
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
        // 기본 동작은 4xx를 예외로 던지므로 onStatus로 가로채고, 그 외 상태는 그대로 예외를 유지한다
        // (토스 5xx를 '주문 없음'으로 오해해 고아로 종결하면 안 되기 때문).
        //
        // ⚠️ 상태 코드를 반드시 직접 확인해야 한다. 토스의 404에는 본문이 실려 오는데
        //    ({"code":"NOT_FOUND_PAYMENT","message":...}), 이를 그대로 역직렬화하면 필드가 전부 빈
        //    객체가 만들어져 "결제가 존재한다"로 오인된다. 그러면 만료 잡이 고아 주문을 영영 종결하지
        //    못한다 — 실제로 그렇게 동작하던 것을 스텁 서버 테스트로 잡았다.
        ResponseEntity<TossPaymentResponse> response = restClient.get()
                .uri("/v1/payments/orders/{orderId}", orderId)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, res) -> { })
                .toEntity(TossPaymentResponse.class);

        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        return Optional.of(toResult(response.getBody()));
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
