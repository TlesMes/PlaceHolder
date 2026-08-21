package com.placeholder.domain.payment.service;

import com.placeholder.domain.payment.dto.MyPaymentsResponse;
import com.placeholder.domain.payment.dto.MyPaymentsResponse.PaymentSummary;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 내 결제·환불 내역 조회 (ADR-019).
 *
 * <p><b>환불 상태를 보여주는 것이 이 조회의 존재 이유다.</b> 취소 ①이 커밋되는 순간 포인트 이력에
 * {@code REFUND}가 찍히고 잔액도 줄어들지만, ②(토스 호출)가 아직이면 현금은 돌아가지 않았다.
 * 포인트 이력만 보는 사용자에겐 그 구분이 전혀 보이지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentHistoryService {

    /** 한 번에 내려줄 최대 건수. 사용자당 충전 건수는 소량이라 페이징 대신 상한으로 충분하다. */
    private static final int MAX_SIZE = 100;

    private final PaymentOrderRepository paymentOrderRepository;

    @Transactional(readOnly = true)
    public MyPaymentsResponse getMyPayments(Long userId) {
        List<PaymentOrder> orders = paymentOrderRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, MAX_SIZE));

        return MyPaymentsResponse.builder()
                .payments(orders.stream().map(this::toSummary).toList())
                .build();
    }

    private PaymentSummary toSummary(PaymentOrder order) {
        return PaymentSummary.builder()
                .orderId(order.getOrderId())
                .amount(order.getAmount())
                .status(order.getStatus().name())
                .canceledAmount(order.getCanceledAmount())
                .createdAt(order.getCreatedAt())
                .approvedAt(order.getApprovedAt())
                .canceledAt(order.getCanceledAt())
                .refundStatus(order.refundStatus().name())
                .build();
    }
}
