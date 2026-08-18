package com.placeholder.domain.payment.controller;

import com.placeholder.domain.payment.dto.PaymentCancelRequest;
import com.placeholder.domain.payment.dto.PaymentCancelResponse;
import com.placeholder.domain.payment.dto.PaymentConfirmRequest;
import com.placeholder.domain.payment.dto.PaymentConfirmResponse;
import com.placeholder.domain.payment.dto.PaymentOrderCreateRequest;
import com.placeholder.domain.payment.dto.PaymentOrderCreateResponse;
import com.placeholder.domain.payment.service.PaymentCancelService;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제(포인트 충전) — 주문 생성 + 동기 승인 (예약자만). ADR-018.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentOrderService paymentOrderService;
    private final PaymentConfirmService paymentConfirmService;
    private final PaymentCancelService paymentCancelService;

    /**
     * 주문 생성 — orderId 발급 + 결제 금액 서버 확정 저장. 응답의 clientKey로 프론트가 결제창을 연다.
     */
    @PreAuthorize("hasRole('BOOKER')")
    @PostMapping("/orders")
    public ResponseEntity<PaymentOrderCreateResponse> createOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody PaymentOrderCreateRequest request) {

        PaymentOrderCreateResponse response =
                paymentOrderService.createOrder(userDetails.getUserId(), request.getAmount());
        return ResponseEntity.ok(response);
    }

    /**
     * 동기 승인 — 프론트가 결제창 성공 후(successUrl) 호출. 서버가 토스 confirm을 확정하고 포인트를 적립한다.
     */
    @PreAuthorize("hasRole('BOOKER')")
    @PostMapping("/confirm")
    public ResponseEntity<PaymentConfirmResponse> confirm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody PaymentConfirmRequest request) {

        PaymentConfirmResponse response = paymentConfirmService.confirm(
                request.getOrderId(), request.getPaymentKey(), request.getAmount(),
                userDetails.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 취소·환불 — 미사용 포인트만큼만 부분 취소한다 (ADR-019).
     *
     * <p>본인 주문만 취소할 수 있다. 셀프 취소 UI는 두지 않고(환불은 고객 문의 경유 정책) API만
     * 제공하므로, 현재는 운영자가 사용자 대신 실행할 수 없다 — ADMIN 역할 도입 시 확장할 지점이다.
     */
    @PreAuthorize("hasRole('BOOKER')")
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<PaymentCancelResponse> cancel(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String orderId,
            @Valid @RequestBody PaymentCancelRequest request) {

        PaymentCancelResponse response = paymentCancelService.cancel(
                orderId, userDetails.getUserId(), request.getReason());
        return ResponseEntity.ok(response);
    }
}
