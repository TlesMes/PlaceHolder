package com.placeholder.payment;

import com.placeholder.domain.payment.client.TossPaymentClientImpl;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findByOrderId}의 404 처리 검증 — 대사(reconciliation)의 전제가 되는 동작이다.
 *
 * <p>토스는 존재하지 않는 orderId에 대해 <b>404와 함께 에러 본문</b>을 돌려준다(실측):
 * <pre>{@code HTTP 404  {"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 정보 입니다."}}</pre>
 *
 * <p>여기서 본문이 있다는 점이 함정이다. 상태 코드만 무시하고 그대로 역직렬화하면 필드가 전부
 * 비어 있는 객체가 만들어져 "결제가 존재한다"로 오해될 수 있고, 그러면 만료 잡이 고아 주문을
 * 영영 종결하지 못한다. 대사 서비스 테스트는 이 메서드를 목킹하므로 그 함정을 잡지 못한다 —
 * 그래서 실제 HTTP 응답을 흉내 내는 스텁 서버로 클라이언트 자체를 검증한다.
 */
class TossPaymentClientFindByOrderIdTest {

    private static HttpServer server;
    private static TossPaymentClientImpl client;

    private static final String NOT_FOUND_BODY =
            "{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 정보 입니다.\"}";
    private static final String FOUND_BODY = """
            {"paymentKey":"tviva_test_1","orderId":"order-found","status":"DONE","totalAmount":10000,
             "mId":"tvivarepublica","method":"카드","currency":"KRW"}
            """;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/payments/orders/", exchange -> {
            boolean found = exchange.getRequestURI().getPath().endsWith("order-found");
            byte[] body = (found ? FOUND_BODY : NOT_FOUND_BODY).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(found ? 200 : 404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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

    @Test
    @DisplayName("존재하지 않는 주문(404 + 에러 본문): Optional.empty — 고아 주문 판정의 전제")
    void findByOrderId_notFound_returnsEmpty() {
        Optional<TossPaymentResult> result = client.findByOrderId("order-missing");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("존재하는 주문(200): 필요한 4개 필드 매핑 (그 외 필드는 무시)")
    void findByOrderId_found_mapsFields() {
        Optional<TossPaymentResult> result = client.findByOrderId("order-found");

        assertThat(result).isPresent();
        TossPaymentResult r = result.orElseThrow();
        assertThat(r.orderId()).isEqualTo("order-found");
        assertThat(r.paymentKey()).isEqualTo("tviva_test_1");
        assertThat(r.totalAmount()).isEqualTo(10_000);
        assertThat(r.isDone()).isTrue();
    }
}
