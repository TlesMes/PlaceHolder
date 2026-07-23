package com.placeholder.domain.payment.controller;

import com.placeholder.domain.payment.dto.TossWebhookPayload;
import com.placeholder.domain.payment.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토스 결제 웹훅 수신 (보조 경로, ADR-018).
 *
 * <p>토스가 JWT 없이 호출하므로 이 경로는 SecurityConfig에서 permitAll이다. 대신 서비스가 페이로드를
 * 신뢰하지 않고 토스에 실제 상태를 재조회해 검증한다(위조 웹훅 방어). 처리 성공/무시 모두 200으로 ack한다.
 */
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    @PostMapping
    public ResponseEntity<Void> handleWebhook(@RequestBody TossWebhookPayload payload) {
        paymentWebhookService.handle(payload);
        return ResponseEntity.ok().build();
    }
}
