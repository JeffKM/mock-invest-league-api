package com.jeffkm.mockleague.order.dto;

import com.jeffkm.mockleague.order.OrderSide;
import com.jeffkm.mockleague.order.OrderType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 요청. type 을 비우면 시장가다(1주차 요청 형식과 호환).
 * 지정가(LIMIT)면 limitPrice 가 필요하고, 시장가면 limitPrice 를 보내면 안 된다(서비스에서 검사).
 * 수량·가격 상한은 금액 계산(가격 × 수량)이 long 을 넘지 않게 하는 입구 방어선이다.
 */
public record PlaceOrderRequest(
    @NotBlank String ticker,
    @NotNull OrderSide side,
    @Positive @Max(1_000_000) long quantity,
    OrderType type,
    @Positive @Max(1_000_000_000) Long limitPrice) {

  /** 시장가 주문(테스트·기존 호출 편의). */
  public PlaceOrderRequest(String ticker, OrderSide side, long quantity) {
    this(ticker, side, quantity, null, null);
  }

  /** 지정가 주문. */
  public static PlaceOrderRequest limit(String ticker, OrderSide side, long quantity, long limitPrice) {
    return new PlaceOrderRequest(ticker, side, quantity, OrderType.LIMIT, limitPrice);
  }

  public OrderType orderType() {
    return type == null ? OrderType.MARKET : type;
  }
}
