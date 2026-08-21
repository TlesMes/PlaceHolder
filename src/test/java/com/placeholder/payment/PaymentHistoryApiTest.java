package com.placeholder.payment;

import com.placeholder.domain.auth.dto.LoginRequest;
import com.placeholder.domain.auth.dto.SignupRequest;
import com.placeholder.domain.auth.service.AuthService;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentCancelService;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 결제·환불 내역 API의 HTTP 계층 검증 (ADR-019).
 *
 * <p>핵심은 {@code refundStatus}다. 취소 ①이 커밋되면 포인트는 즉시 빠져나가지만 ②(토스 호출)가
 * 아직이면 <b>현금은 돌아가지 않았다.</b> 그 구분이 응답에 실려야 화면이 "환불 완료"라고
 * 거짓말하지 않는다.
 */
@SuppressWarnings("null")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentHistoryApiTest extends MySQLIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmService confirmService;
    @Autowired PaymentCancelService cancelService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean TossPaymentClient tossClient;

    private String bookerToken;
    private Long bookerId;

    @BeforeEach
    void setUp() {
        when(tossClient.confirm(any(), any(), anyInt())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), inv.getArgument(1), "DONE", inv.getArgument(2)));
        when(tossClient.cancel(any(), any(), anyInt(), any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "CANCELED", inv.getArgument(2)));

        String email = "pay-history-" + uniqueId() + "@test.com";
        bookerToken = signup(email, "BOOKER");
        bookerId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @Test
    @DisplayName("취소 없는 결제 → refundStatus NONE")
    void myPayments_notCanceled_returnsNone() throws Exception {
        String orderId = chargedOrder(10_000);

        mockMvc.perform(get("/api/payments/my").header("Authorization", "Bearer " + bookerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].orderId").value(orderId))
                .andExpect(jsonPath("$.payments[0].amount").value(10_000))
                .andExpect(jsonPath("$.payments[0].status").value("DONE"))
                .andExpect(jsonPath("$.payments[0].refundStatus").value("NONE"));
    }

    @Test
    @DisplayName("정상 취소된 결제 → refundStatus COMPLETED")
    void myPayments_canceled_returnsCompleted() throws Exception {
        String orderId = chargedOrder(10_000);
        cancelService.cancel(orderId, bookerId, "고객 요청");

        mockMvc.perform(get("/api/payments/my").header("Authorization", "Bearer " + bookerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].status").value("CANCELED"))
                .andExpect(jsonPath("$.payments[0].canceledAmount").value(10_000))
                .andExpect(jsonPath("$.payments[0].refundStatus").value("COMPLETED"));
    }

    @Test
    @DisplayName("크래시 창(포인트만 회수됨) → refundStatus PENDING — 화면이 '환불 완료'라고 하면 안 된다")
    void myPayments_crashWindow_returnsPending() throws Exception {
        String orderId = chargedOrder(10_000);
        cancelService.cancel(orderId, bookerId, "고객 요청");
        // ②를 부르기 전에 죽은 상태를 재현 — 확인 시각만 지운다(포인트는 이미 회수됨)
        int updated = jdbcTemplate.update(
                "update payment_orders set cancel_confirmed_at = null where order_id = ?", orderId);
        org.assertj.core.api.Assertions.assertThat(updated).isEqualTo(1);

        mockMvc.perform(get("/api/payments/my").header("Authorization", "Bearer " + bookerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].status").value("CANCELED"))
                .andExpect(jsonPath("$.payments[0].refundStatus").value("PENDING"));
    }

    @Test
    @DisplayName("타인의 주문은 보이지 않는다")
    void myPayments_otherUsersOrders_areNotVisible() throws Exception {
        chargedOrder(10_000);

        String otherEmail = "pay-history-other-" + uniqueId() + "@test.com";
        String otherToken = signup(otherEmail, "BOOKER");

        mockMvc.perform(get("/api/payments/my").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments").isEmpty());
    }

    @Test
    @DisplayName("토큰 없음 → 401")
    void myPayments_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/payments/my"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PROVIDER 토큰 → 403 (@PreAuthorize 역할 제어)")
    void myPayments_asProvider_returns403() throws Exception {
        String providerToken = signup("pay-history-prov-" + uniqueId() + "@test.com", "PROVIDER");

        mockMvc.perform(get("/api/payments/my").header("Authorization", "Bearer " + providerToken))
                .andExpect(status().isForbidden());
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
