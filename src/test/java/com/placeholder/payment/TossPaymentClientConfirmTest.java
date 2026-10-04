package com.placeholder.payment;

import com.placeholder.domain.payment.client.TossPaymentClientImpl;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
import com.placeholder.global.exception.custom.PaymentResultUnknownException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code confirm()}의 실패 분류 검증 (ADR-022) — "토스가 거절함"과 "결과 모름"을 가르는 지점.
 *
 * <p>이 분류가 틀리면 돈이 걸린다. 결과 모름을 거절로 분류하면 토스가 승인한 결제가 FAILED로 굳고
 * (고객 돈은 나갔는데 포인트 없음), 반대로 거절을 결과 모름으로 분류하면 대사가 정리할 때까지
 * 사용자가 "확인 중"을 보게 된다. 손실이 비대칭이므로 애매한 쪽은 결과 모름으로 보낸다.
 *
 * <p>서비스 테스트는 클라이언트를 목킹해 이 분류를 가정하므로, 실제 HTTP 응답으로 클라이언트 자체를 본다
 * (PR #25·#26과 같은 이유).
 */
class TossPaymentClientConfirmTest {

    private static HttpServer server;
    private static TossPaymentClientImpl client;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status;
            String payload;
            if (body.contains("order-reject")) {
                status = 400;
                payload = "{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과 혹은 잔액부족으로 결제에 실패했습니다.\"}";
            } else if (body.contains("order-already")) {
                status = 400;
                payload = "{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}";
            } else if (body.contains("order-5xx")) {
                status = 500;
                payload = "{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"내부 시스템 처리 작업이 실패했습니다.\"}";
            } else {
                status = 200;
                payload = "{\"paymentKey\":\"pk_1\",\"orderId\":\"order-ok\",\"status\":\"DONE\","
                        + "\"totalAmount\":10000,\"balanceAmount\":10000}";
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
                "http://localhost:" + server.getAddress().getPort(), "test_secret", 1_000, 1_000);
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @Test
    @DisplayName("200 DONE → 승인 결과 매핑")
    void ok_mapsResult() {
        TossPaymentResult result = client.confirm("pk_1", "order-ok", 10_000);

        assertThat(result.isDone()).isTrue();
        assertThat(result.totalAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("4xx 거절(카드 한도 초과 등) → 승인 실패. 승인되지 않은 것이 확실하다")
    void clientError_isRejection() {
        assertThatThrownBy(() -> client.confirm("pk_1", "order-reject", 10_000))
                .isInstanceOf(PaymentConfirmFailedException.class);
    }

    @Test
    @DisplayName("4xx라도 ALREADY_PROCESSED_PAYMENT → 결과 모름. 거절이 아니라 이미 승인됐다는 뜻일 수 있다")
    void alreadyProcessed_isUnknown() {
        assertThatThrownBy(() -> client.confirm("pk_1", "order-already", 10_000))
                .isInstanceOf(PaymentResultUnknownException.class);
    }

    @Test
    @DisplayName("5xx → 결과 모름. 토스 내부 오류는 처리 여부를 알려주지 않는다")
    void serverError_isUnknown() {
        assertThatThrownBy(() -> client.confirm("pk_1", "order-5xx", 10_000))
                .isInstanceOf(PaymentResultUnknownException.class);
    }
}
