package com.jeffkm.mockleague.order.dto;

import com.jeffkm.mockleague.order.OrderSide;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 시장가 주문 요청. 수량 상한은 금액 계산(가격 × 수량)이 long 을 넘지 않게 하는 입구 방어선이다.
 */
public record PlaceOrderRequest(
    @NotBlank String ticker,
    @NotNull OrderSide side,
    @Positive @Max(1_000_000) long quantity) {}
