package com.placeholder.payment;

import com.placeholder.domain.payment.client.TossPaymentClientImpl;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.global.exception.custom.PaymentCancelFailedException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code cancel()}의 실제 HTTP 규격 검증 (ADR-019).
 *
 * <p><b>왜 스텁 서버인가:</b> 취소 로직 테스트들은 {@code TossPaymentClient}를 목킹하므로
 * 요청 경로·바디 키 이름·헤더·응답 매핑이 한 번도 실행되지 않는다. 이 프로젝트는 이미 같은
 * 자리에서 데였다 — 대사 서비스 테스트가 {@code findByOrderId}를 목킹한 탓에 404 본문을
 * 결제 정보로 오인하던 버그를 놓쳤고(PR #25), JDK {@link HttpServer} 스텁으로만 잡혔다.
 * 취소는 실패하면 돈이 걸린 경로라 같은 방식으로 클라이언트 자체를 검증한다.
 */
class TossPaymentClientCancelTest {

    private static HttpServer server;
    private static TossPaymentClientImpl client;

    /** 스텁이 관측한 마지막 요청 — 우리가 실제로 무엇을 보냈는지 검사하기 위해 기록한다. */
    private static final Map<String, String> lastRequest = new ConcurrentHashMap<>();

    private static final String CANCELED_BODY = """
            {"paymentKey":"tviva_test_1","orderId":"order-1","status":"CANCELED","totalAmount":10000,
             "balanceAmount":0,"cancels":[{"cancelAmount":10000,"cancelReason":"고객 요청"}]}
            """;
    private static final String PARTIAL_BODY = """
            {"paymentKey":"tviva_test_1","orderId":"order-1","status":"PARTIAL_CANCELED","totalAmount":10000,
             "balanceAmount":4000,"cancels":[{"cancelAmount":6000,"cancelReason":"고객 요청"}]}
            """;
    private static final String FORBIDDEN_BODY =
            "{\"code\":\"NOT_CANCELABLE_AMOUNT\",\"message\":\"취소 가능 금액을 초과했습니다.\"}";

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/payments/", exchange -> {
            lastRequest.put("method", exchange.getRequestMethod());
            lastRequest.put("path", exchange.getRequestURI().getPath());
            String idem = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            lastRequest.put("idempotencyKey", idem == null ? "" : idem);
            lastRequest.put("body",
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String path = exchange.getRequestURI().getPath();
            int status;
            String payload;
            if (path.contains("pk_forbidden")) {
                status = 403;
                payload = FORBIDDEN_BODY;
            } else if (path.contains("pk_partial")) {
                status = 200;
                payload = PARTIAL_BODY;
            } else {
                status = 200;
                payload = CANCELED_BODY;
            }

            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        client = new TossPaymentClientImpl(
                "http://localhost:" + server.getAddress().getPort(), "test_secret", 3_000, 3_000);
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @BeforeEach
    void clearObserved() {
        lastRequest.clear();
    }

    @Test
    @DisplayName("전액 취소: POST /v1/payments/{paymentKey}/cancel 로 규격대로 요청한다")
    void cancel_sendsCorrectRequest() {
        TossPaymentResult result = client.cancel("pk_1", "고객 요청", 10_000, "cancel-order-1-10000");

        assertThat(lastRequest.get("method")).isEqualTo("POST");
        assertThat(lastRequest.get("path")).isEqualTo("/v1/payments/pk_1/cancel");
        // 바디 키 이름이 틀리면 토스가 거부한다 — 목킹으로는 절대 드러나지 않는 부분
        assertThat(lastRequest.get("body"))
                .contains("\"cancelReason\":\"고객 요청\"")
                .contains("\"cancelAmount\":10000");
        assertThat(result.status()).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 실제로 전송된다 (네트워크 재시도 시 중복 취소 방어)")
    void cancel_sendsIdempotencyKey() {
        client.cancel("pk_1", "고객 요청", 10_000, "cancel-order-1-10000");

        assertThat(lastRequest.get("idempotencyKey")).isEqualTo("cancel-order-1-10000");
    }

    @Test
    @DisplayName("부분 취소 응답: PARTIAL_CANCELED 상태를 그대로 매핑한다")
    void cancel_partial_mapsStatus() {
        TossPaymentResult result = client.cancel("pk_partial", "고객 요청", 6_000, "cancel-order-1-6000");

        assertThat(result.status()).isEqualTo("PARTIAL_CANCELED");
        assertThat(result.isDone()).isFalse();
    }

    @Test
    @DisplayName("토스가 거부(403 + 에러 본문): 예외로 올린다 — 삼키면 포인트 복구가 안 된다")
    void cancel_rejected_throws() {
        assertThatThrownBy(() -> client.cancel("pk_forbidden", "고객 요청", 99_999, "cancel-x-99999"))
                .isInstanceOf(PaymentCancelFailedException.class);
    }
}
