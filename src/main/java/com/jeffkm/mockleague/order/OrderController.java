package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.account.AccountController;
import com.jeffkm.mockleague.common.ApiResponse;
import com.jeffkm.mockleague.order.dto.OrderResponse;
import com.jeffkm.mockleague.order.dto.PlaceOrderRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contests/{contestId}/orders")
public class OrderController {

  public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final OrderFacade orderFacade;

  public OrderController(OrderFacade orderFacade) {
    this.orderFacade = orderFacade;
  }

  /**
   * 시장가 주문. 처음 요청이든 같은 키의 재요청이든 200 과 같은 본문을 돌려준다.
   * (재요청에 201/200 을 나누면 클라이언트가 "처음과 같은 응답"을 받지 못한다)
   */
  @PostMapping
  public ApiResponse<OrderResponse> placeMarketOrder(
      @PathVariable Long contestId,
      @RequestHeader(AccountController.USER_HEADER) Long userId,
      @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
      @Valid @RequestBody PlaceOrderRequest request) {
    return ApiResponse.ok(orderFacade.placeMarketOrder(contestId, userId, idempotencyKey, request));
  }
}
