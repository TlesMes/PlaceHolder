package com.placeholder.payment;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.dto.TossWebhookPayload;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.domain.payment.service.PaymentReconciliationService;
import com.placeholder.domain.payment.service.PaymentWebhookService;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>승인 결과를 모르는 채로 끝난 결제</b> — 토스는 승인했는데 우리는 그 응답을 받지 못한 경우.
 *
 * <p>읽기 타임아웃은 "실패"가 아니라 "결과 모름"이다. 요청은 토스에 도달해 처리됐을 수 있다.
 * 이때 고객의 돈은 이미 빠져나갔으므로, 어떤 경로로든 결국 포인트가 적립돼야 한다.
 *
 * <p><b>왜 목킹이 아니라 스텁 서버인가.</b> 목으로 "타임아웃이면 이 예외가 난다"를 가정하면
 * 그 가정 자체가 검증 대상에서 빠진다. 실제 {@code TossPaymentClientImpl}에 실제 읽기 타임아웃을
 * 일으켜, 클라이언트가 무엇을 던지든 최종 상태만 본다.
 *
 * <p>스텁은 토스를 이렇게 흉내 낸다: 승인 요청을 받는 <b>즉시</b> 승인 처리를 끝내고(돈이 빠져나감),
 * 응답만 {@link #UNKNOWN_DELAY_MS}만큼 늦게 보낸다. 같은 결제에 두 번째 승인 요청이 오면
 * {@code ALREADY_PROCESSED_PAYMENT}로 거절한다(토스 문서의 오류 코드. 처리 중인 결제에 대한 실제
 * 응답은 확인하지 않았다 — 수정 후에는 두 번째 요청이 토스까지 가지 않으므로 결론에 영향 없음).
 */
@SpringBootTest(properties = {
        "toss.connect-timeout-ms=1000",
        "toss.read-timeout-ms=" + PaymentConfirmUnknownResultTest.READ_TIMEOUT_MS
})
@ActiveProfiles("test")
class PaymentConfirmUnknownResultTest extends MySQLIntegrationTest {

    static final int READ_TIMEOUT_MS = 1500;
    /** 읽기 타임아웃보다 길게 — 우리는 끊고, 토스는 승인을 마친 상태를 만든다. */
    private static final int UNKNOWN_DELAY_MS = 3000;
    /** 읽기 타임아웃보다 짧게 — 첫 요청이 정상 응답을 받기 전에 두 번째 요청이 끼어드는 창. */
    private static final int IN_FLIGHT_DELAY_MS = 800;
    private static final int AMOUNT = 10_000;

    private static final HttpServer STUB = TossStub.start();

    @DynamicPropertySource
    static void tossBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("toss.api-base-url", () -> "http://localhost:" + STUB.getAddress().getPort());
    }

    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmService confirmService;
    @Autowired PaymentWebhookService webhookService;
    @Autowired PaymentReconciliationService reconciliationService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("읽기 타임아웃 뒤 토스는 승인 → 웹훅이 도착하면 적립된다")
    void unknownResult_thenWebhook_credits() {
        Long userId = persistBooker();
        String orderId = orderService.createOrder(userId, AMOUNT).getOrderId();
        String paymentKey = "pk_" + orderId;
        TossStub.delay(orderId, UNKNOWN_DELAY_MS);

        String confirmOutcome = attempt(() -> confirmService.confirm(orderId, paymentKey, AMOUNT, userId));
        String webhookOutcome = attempt(() -> webhookService.handle(webhookPayload(orderId, paymentKey)));

        assertCreditedOnce(userId, orderId, "승인=" + confirmOutcome + ", 웹훅=" + webhookOutcome);
    }

    @Test
    @DisplayName("읽기 타임아웃 뒤 토스는 승인 → 웹훅이 유실돼도 대사가 적립한다")
    void unknownResult_thenReconciliation_credits() {
        Long userId = persistBooker();
        String orderId = orderService.createOrder(userId, AMOUNT).getOrderId();
        TossStub.delay(orderId, UNKNOWN_DELAY_MS);

        String confirmOutcome = attempt(() -> confirmService.confirm(orderId, "pk_" + orderId, AMOUNT, userId));

        // 보정 잡의 대상 구간(생성 5분~2시간 전)에 들어가도록 생성 시각을 당긴다. 구간은 이 주문만
        // 들어오게 좁힌다 — 다른 테스트가 남긴 주문이 배치 상한을 채우지 않도록.
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(6);
        jdbcTemplate.update("update payment_orders set created_at = ? where order_id = ?",
                Timestamp.valueOf(createdAt), orderId);
        String reconcileOutcome = attempt(() -> reconciliationService.reconcile(
                createdAt.minusSeconds(1), createdAt.plusSeconds(1), false));

        assertCreditedOnce(userId, orderId, "승인=" + confirmOutcome + ", 대사=" + reconcileOutcome);
    }

    @Test
    @DisplayName("첫 승인이 진행 중일 때 두 번째 승인 요청(새로고침) → 토스 호출 1회, 적립 1회")
    void secondConfirmWhileFirstInFlight_callsTossOnce() throws Exception {
        Long userId = persistBooker();
        String orderId = orderService.createOrder(userId, AMOUNT).getOrderId();
        String paymentKey = "pk_" + orderId;
        TossStub.delay(orderId, IN_FLIGHT_DELAY_MS);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() ->
                    attempt(() -> confirmService.confirm(orderId, paymentKey, AMOUNT, userId)));
            // 첫 요청이 토스에 도달해 응답을 기다리는 중임을 확인한 뒤에 두 번째를 보낸다
            assertThat(TossStub.awaitFirstConfirm(orderId)).isTrue();
            Future<String> second = executor.submit(() ->
                    attempt(() -> confirmService.confirm(orderId, paymentKey, AMOUNT, userId)));

            String outcomes = "첫 요청=" + first.get(30, TimeUnit.SECONDS)
                    + ", 두 번째=" + second.get(30, TimeUnit.SECONDS);

            assertThat(TossStub.confirmCalls(orderId))
                    .as("토스 승인 호출은 한 번이어야 한다 (%s)", outcomes)
                    .isEqualTo(1);
            assertCreditedOnce(userId, orderId, outcomes);
        } finally {
            executor.shutdownNow();
        }
    }

    // --- 단정 ---

    private void assertCreditedOnce(Long userId, String orderId, String outcomes) {
        assertThat(paymentOrderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .as("토스가 승인한 주문은 DONE이어야 한다 (%s)", outcomes)
                .isEqualTo(PaymentStatus.DONE);
        assertThat(bookerAccountRepository.findByUserId(userId).orElseThrow().getBalance())
                .as("돈이 빠져나갔으니 포인트가 적립돼야 한다 (%s)", outcomes)
                .isEqualTo(AMOUNT);
        assertThat(pointTransactionRepository.findByTypeAndUserId(TransactionType.CHARGE, userId))
                .as("적립은 정확히 한 번 (%s)", outcomes)
                .hasSize(1);
    }

    /** 결과를 문자열로 남긴다. 예외도 삼키지 않고 단정 메시지에 실어 보낸다. */
    private static String attempt(Runnable action) {
        try {
            action.run();
            return "정상 반환";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + "(" + e.getMessage() + ")";
        }
    }

    // --- 픽스처 ---

    private Long persistBooker() {
        User booker = userRepository.save(User.builder()
                .email("booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder().user(booker).build());
        return booker.getId();
    }

    private TossWebhookPayload webhookPayload(String orderId, String paymentKey) {
        TossWebhookPayload payload = new TossWebhookPayload();
        TossWebhookPayload.Data data = new TossWebhookPayload.Data();
        setField(data, "paymentKey", paymentKey);
        setField(data, "orderId", orderId);
        setField(data, "status", "DONE");
        setField(payload, "eventType", "PAYMENT_STATUS_CHANGED");
        setField(payload, "data", data);
        return payload;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 토스 스텁. 승인 요청을 받으면 즉시 승인 처리를 끝내고 응답만 지연시킨다.
     * 동시 요청을 받아야 하므로 실행기를 따로 둔다(기본값은 요청을 한 줄로 세운다).
     */
    static final class TossStub {
        private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern PAYMENT_KEY = Pattern.compile("\"paymentKey\"\\s*:\\s*\"([^\"]+)\"");

        private static final Map<String, Integer> delays = new ConcurrentHashMap<>();
        /** orderId → paymentKey. 여기 들어 있으면 토스 쪽에서는 승인이 끝난 결제다. */
        private static final Map<String, String> approved = new ConcurrentHashMap<>();
        private static final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private static final Map<String, CountDownLatch> firstCall = new ConcurrentHashMap<>();
        private static final List<String> log = new CopyOnWriteArrayList<>();

        static HttpServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
                server.createContext("/v1/payments/confirm", TossStub::confirm);
                server.createContext("/v1/payments/orders/", TossStub::findByOrderId);
                server.createContext("/v1/payments/", TossStub::getPayment);
                server.setExecutor(Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "toss-stub");
                    t.setDaemon(true);
                    return t;
                }));
                server.start();
                return server;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static void delay(String orderId, int millis) {
            delays.put(orderId, millis);
            firstCall.put(orderId, new CountDownLatch(1));
        }

        static int confirmCalls(String orderId) {
            AtomicInteger count = calls.get(orderId);
            return count == null ? 0 : count.get();
        }

        static boolean awaitFirstConfirm(String orderId) throws InterruptedException {
            return firstCall.get(orderId).await(10, TimeUnit.SECONDS);
        }

        private static void confirm(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String orderId = group(ORDER_ID, body);
            String paymentKey = group(PAYMENT_KEY, body);
            calls.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();

            if (approved.putIfAbsent(orderId, paymentKey) != null) {
                respond(exchange, 400, "{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}");
                return;
            }
            CountDownLatch latch = firstCall.get(orderId);
            if (latch != null) {
                latch.countDown();
            }
            sleep(delays.getOrDefault(orderId, 0));
            respond(exchange, 200, done(orderId, paymentKey));
        }

        private static void findByOrderId(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String orderId = path.substring(path.lastIndexOf('/') + 1);
            String paymentKey = approved.get(orderId);
            if (paymentKey == null) {
                respond(exchange, 404, "{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 정보 입니다.\"}");
            } else {
                respond(exchange, 200, done(orderId, paymentKey));
            }
        }

        private static void getPayment(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String paymentKey = path.substring(path.lastIndexOf('/') + 1);
            String orderId = approved.entrySet().stream()
                    .filter(e -> e.getValue().equals(paymentKey))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (orderId == null) {
                respond(exchange, 404, "{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 정보 입니다.\"}");
            } else {
                respond(exchange, 200, done(orderId, paymentKey));
            }
        }

        private static String done(String orderId, String paymentKey) {
            return "{\"paymentKey\":\"" + paymentKey + "\",\"orderId\":\"" + orderId
                    + "\",\"status\":\"DONE\",\"totalAmount\":" + AMOUNT + ",\"balanceAmount\":" + AMOUNT + "}";
        }

        private static void respond(HttpExchange exchange, int status, String json) {
            // 클라이언트가 타임아웃으로 먼저 끊었으면 쓰기가 실패한다 — 토스 입장에선 응답 유실이다
            try (exchange) {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException e) {
                log.add("응답 유실: " + e.getMessage());
            }
        }

        private static String group(Pattern pattern, String body) {
            Matcher m = pattern.matcher(body);
            return m.find() ? m.group(1) : "?";
        }

        private static void sleep(int millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
