package com.placeholder.domain.payment.controller;

import com.placeholder.domain.payment.dto.PaymentConfirmRequest;
import com.placeholder.domain.payment.dto.PaymentConfirmResponse;
import com.placeholder.domain.payment.dto.PaymentOrderCreateRequest;
import com.placeholder.domain.payment.dto.PaymentOrderCreateResponse;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
}
