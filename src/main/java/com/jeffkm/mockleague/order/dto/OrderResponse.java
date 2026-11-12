package com.jeffkm.mockleague.order.dto;

import com.jeffkm.mockleague.order.Order;
import com.jeffkm.mockleague.order.OrderSide;
import com.jeffkm.mockleague.order.OrderStatus;
import com.jeffkm.mockleague.order.OrderType;
import java.time.Instant;

/**
 * 주문 결과. 주문 시점의 사실만 담는다(주문 후 잔고는 넣지 않는다).
 * 같은 Idempotency-Key 로 다시 보냈을 때 "처음과 같은 응답"을 주려면, 나중에 바뀌는 값이 섞이면 안 된다.
 * 예외: 지정가 주문은 대기 중에 체결·만료될 수 있다. 그때 재요청에는 "지금 상태"를 돌려준다(같은 주문이라는 것은 같다).
 * 체결 전에는 filledPrice·amount·fee·filledTick·filledAt 이 null 이다.
 */
public record OrderResponse(
    Long orderId,
    String ticker,
    OrderSide side,
    OrderType type,
    long quantity,
    Long limitPrice,
    OrderStatus status,
    Long filledPrice,
    Long amount,
    Long fee,
    Integer filledTick,
    Instant filledAt) {

  public static OrderResponse from(Order o) {
    Long amount = o.getStatus() == OrderStatus.FILLED ? o.filledAmount() : null;
    return new OrderResponse(o.getId(), o.getCompany().getTicker(), o.getSide(), o.getOrderType(),
        o.getQuantity(), o.getLimitPrice(), o.getStatus(), o.getFilledPrice(), amount, o.getFee(),
        o.getFilledTick(), o.getFilledAt());
  }
}
