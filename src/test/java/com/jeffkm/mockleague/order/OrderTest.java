package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OrderTest {

  private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

  private Order newMarketBuy() {
    return Order.market(null, null, OrderSide.BUY, 10, "key-1", DAY, 0);
  }

  @Test
  void 접수하면_대기_상태다() {
    Order o = newMarketBuy();

    assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(o.getOrderType()).isEqualTo(OrderType.MARKET);
    assertThat(o.getLimitPrice()).isNull();
  }

  @Test
  void 체결하면_체결가와_수수료가_남고_체결_금액을_계산할_수_있다() {
    Order o = newMarketBuy();

    o.fill(84_000, 4_200, 3, Instant.parse("2026-12-01T00:15:00Z"));

    assertThat(o.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(o.getFilledPrice()).isEqualTo(84_000);
    assertThat(o.getFee()).isEqualTo(4_200);
    assertThat(o.filledAmount()).isEqualTo(840_000);
  }

  @Test
  void I10_한_번_끝난_주문은_다시_체결_취소_만료할_수_없다() {
    Order o = newMarketBuy();
    o.fill(84_000, 4_200, 3, Instant.now());

    assertThatThrownBy(() -> o.fill(84_000, 4_200, 4, Instant.now())).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(o::cancel).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(o::expire).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 체결_전에는_체결_금액을_물을_수_없다() {
    assertThatThrownBy(() -> newMarketBuy().filledAmount()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 지정가_주문은_지정가를_가지고_취소할_수_있다() {
    Order o = Order.limit(null, null, OrderSide.SELL, 5, 90_000, "key-2", DAY, 1);

    o.cancel();

    assertThat(o.getLimitPrice()).isEqualTo(90_000);
    assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELED);
  }

  @Test
  void 수량이나_키가_잘못되면_접수할_수_없다() {
    assertThatThrownBy(() -> Order.market(null, null, OrderSide.BUY, 0, "k", DAY, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.market(null, null, OrderSide.BUY, 1, " ", DAY, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.market(null, null, OrderSide.BUY, 1, "x".repeat(65), DAY, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
