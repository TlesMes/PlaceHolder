package com.placeholder.domain.payment.service;

import com.placeholder.domain.payment.dto.PaymentOrderCreateResponse;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 주문 생성 — 결제 플로우의 시작점 (ADR-018).
 *
 * <p>서버가 orderId를 발급하고 결제 금액(amount)을 <b>여기서 확정 저장</b>한다. 이후 confirm/webhook이
 * 들고 오는 금액은 이 저장값과 대조되므로, 클라이언트가 금액을 조작해도 위변조가 걸러진다.
 */
@Service
@RequiredArgsConstructor
public class PaymentOrderService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final UserRepository userRepository;

    @Value("${toss.client-key:}")
    private String clientKey;

    @Transactional
    public PaymentOrderCreateResponse createOrder(Long userId, int amount) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserNotFoundException("예약자를 찾을 수 없습니다"));

        String orderId = UUID.randomUUID().toString();
        paymentOrderRepository.save(PaymentOrder.builder()
                .orderId(orderId)
                .user(user)
                .amount(amount)
                .build());

        return PaymentOrderCreateResponse.builder()
                .orderId(orderId)
                .amount(amount)
                .clientKey(clientKey)
                .build();
    }
}
