package com.placeholder.domain.payment.dto;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PaymentOrderCreateRequest {

    @Min(value = 1, message = "충전 금액은 1 이상이어야 합니다")
    private int amount;
}
