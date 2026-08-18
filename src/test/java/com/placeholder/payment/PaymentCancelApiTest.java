package com.placeholder.payment;

import com.placeholder.domain.auth.dto.LoginRequest;
import com.placeholder.domain.auth.dto.SignupRequest;
import com.placeholder.domain.auth.service.AuthService;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.PaymentCancelFailedException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 취소 API의 <b>HTTP 계층</b> 검증 (ADR-019).
 *
 * <p>서비스 테스트가 아무리 촘촘해도 검증되지 않는 코드가 남는다 — {@code @PreAuthorize} 역할 제어,
 * {@code @Valid} 요청 검증, 그리고 {@code GlobalExceptionHandler}의 상태코드 매핑이다. 이들은
 * 서비스를 직접 호출하는 테스트에서는 <b>한 줄도 실행되지 않는다.</b> 돈이 걸린 엔드포인트라
 * "권한 없는 사람이 부를 수 있는가"는 반드시 HTTP로 확인한다.
 */
@SuppressWarnings("null")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentCancelApiTest extends MySQLIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmService confirmService;

    @MockitoBean TossPaymentClient tossClient;

    private String bookerToken;
    private Long bookerId;

    @BeforeEach
    void setUp() {
        when(tossClient.confirm(any(), any(), anyInt())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), inv.getArgument(1), "DONE", inv.getArgument(2)));
        when(tossClient.cancel(any(), any(), anyInt(), any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "CANCELED", inv.getArgument(2)));

        String email = "cancel-api-" + uniqueId() + "@test.com";
        bookerToken = signup(email, "BOOKER");
        bookerId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @Test
    @DisplayName("BOOKER 본인 → 200 + 취소 결과 JSON")
    void cancel_asOwner_returns200() throws Exception {
        String orderId = chargedOrder(10_000);

        mockMvc.perform(post("/api/payments/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + bookerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"고객 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.canceledAmount").value(10_000))
                .andExpect(jsonPath("$.totalCanceledAmount").value(10_000))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    @DisplayName("토큰 없음 → 401 (돈 만지는 엔드포인트가 익명에게 열려 있으면 안 된다)")
    void cancel_anonymous_returns401() throws Exception {
        String orderId = chargedOrder(10_000);

        mockMvc.perform(post("/api/payments/{orderId}/cancel", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"고객 요청\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PROVIDER 토큰 → 403 (@PreAuthorize 역할 제어)")
    void cancel_asProvider_returns403() throws Exception {
        String orderId = chargedOrder(10_000);
        String providerToken = signup("cancel-api-prov-" + uniqueId() + "@test.com", "PROVIDER");

        mockMvc.perform(post("/api/payments/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + providerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"고객 요청\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("취소 사유 누락 → 400 VALIDATION_FAILED (@Valid)")
    void cancel_blankReason_returns400() throws Exception {
        String orderId = chargedOrder(10_000);

        mockMvc.perform(post("/api/payments/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + bookerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("취소 불가 상태(READY 주문) → 400 PAYMENT_CANCEL_NOT_ALLOWED")
    void cancel_notAllowed_returns400() throws Exception {
        String orderId = orderService.createOrder(bookerId, 10_000).getOrderId();

        mockMvc.perform(post("/api/payments/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + bookerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"고객 요청\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_CANCEL_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("없는 주문 → 404 RESOURCE_NOT_FOUND")
    void cancel_unknownOrder_returns404() throws Exception {
        mockMvc.perform(post("/api/payments/{orderId}/cancel", "no-such-order")
                        .header("Authorization", "Bearer " + bookerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"고객 요청\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("토스 취소 실패 → 502 PAYMENT_CANCEL_FAILED (하류 장애를 우리 잘못으로 표시하지 않는다)")
    void cancel_tossFails_returns502() throws Exception {
        String orderId = chargedOrder(10_000);
        when(tossClient.cancel(any(), any(), anyInt(), any()))
                .thenThrow(new PaymentCancelFailedException("토스 장애"));

        mockMvc.perform(post("/api/payments/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + bookerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"고객 요청\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_CANCEL_FAILED"));
    }

    // --- 헬퍼 ---

    private String chargedOrder(int amount) {
        String orderId = orderService.createOrder(bookerId, amount).getOrderId();
        confirmService.confirm(orderId, "pk_" + orderId, amount, bookerId);
        return orderId;
    }

    private String signup(String email, String role) {
        authService.signup(SignupRequest.builder().email(email).password("pass1234").role(role).build());
        return authService.login(
                LoginRequest.builder().email(email).password("pass1234").build()).getAccessToken();
    }
}
