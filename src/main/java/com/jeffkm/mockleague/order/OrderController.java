package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.account.AccountController;
import com.jeffkm.mockleague.common.ApiResponse;
import com.jeffkm.mockleague.order.dto.OrderResponse;
import com.jeffkm.mockleague.order.dto.PlaceOrderRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
  private final OrderService orderService;

  public OrderController(OrderFacade orderFacade, OrderService orderService) {
    this.orderFacade = orderFacade;
    this.orderService = orderService;
  }

  /**
   * 주문(시장가·지정가). 처음 요청이든 같은 키의 재요청이든 200 과 같은 주문을 돌려준다.
   * (재요청에 201/200 을 나누면 클라이언트가 "처음과 같은 응답"을 받지 못한다)
   * 시장가는 FILLED, 지정가는 PENDING 으로 응답한다.
   */
  @PostMapping
  public ApiResponse<OrderResponse> placeOrder(
      @PathVariable Long contestId,
      @RequestHeader(AccountController.USER_HEADER) Long userId,
      @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
      @Valid @RequestBody PlaceOrderRequest request) {
    return ApiResponse.ok(orderFacade.placeOrder(contestId, userId, idempotencyKey, request));
  }

  /** 내 주문 최근 50건(최신순). */
  @GetMapping
  public ApiResponse<List<OrderResponse>> recentOrders(
      @PathVariable Long contestId,
      @RequestHeader(AccountController.USER_HEADER) Long userId) {
    return ApiResponse.ok(orderService.recentOrders(contestId, userId));
  }

  /** 대기 중인 지정가 주문 취소(F9). 이미 끝난 주문이면 409 ORDER_NOT_CANCELABLE. */
  @DeleteMapping("/{orderId}")
  public ApiResponse<OrderResponse> cancel(
      @PathVariable Long contestId,
      @PathVariable Long orderId,
      @RequestHeader(AccountController.USER_HEADER) Long userId) {
    return ApiResponse.ok(orderService.cancel(contestId, userId, orderId));
  }
}
