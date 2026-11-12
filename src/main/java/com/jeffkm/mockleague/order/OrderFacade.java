package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.order.dto.OrderResponse;
import com.jeffkm.mockleague.order.dto.PlaceOrderRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 주문 진입점. 트랜잭션은 열지 않고, 충돌로 실패한 주문을 "새 트랜잭션으로" 다시 시도한다.
 *
 * <p>왜 OrderService 안에서 재시도하지 않나: 실패한 트랜잭션은 이미 롤백 표시가 됐고,
 * 영속성 컨텍스트에는 옛 값이 남아 있다. 같은 트랜잭션 안에서 다시 해 봐야 같은 옛 값으로 또 실패한다.
 * 그래서 @Transactional 메서드를 "바깥에서" 다시 불러 처음부터(새로 읽기부터) 한다.
 *
 * <p>다시 시도하는 실패 두 가지:
 * <ul>
 *   <li>낙관적 락 충돌: 그 사이 다른 요청이 계좌를 먼저 바꿨다. 새로 읽으면 성공할 수 있다.</li>
 *   <li>유니크·PK 충돌: 같은 키 주문이나 첫 보유 행을 다른 요청이 먼저 INSERT 했다.
 *       다시 하면 "이미 있음"을 보고 처음 결과를 돌려주거나(멱등), 있는 보유 행에 더한다.</li>
 * </ul>
 */
@Service
public class OrderFacade {

  private static final Logger log = LoggerFactory.getLogger(OrderFacade.class);

  private final OrderService orderService;
  private final MeterRegistry meterRegistry;
  private final int maxAttempts;

  public OrderFacade(OrderService orderService, MeterRegistry meterRegistry,
      @Value("${league.order.max-attempts:5}") int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("league.order.max-attempts 는 1 이상이어야 합니다: " + maxAttempts);
    }
    this.orderService = orderService;
    this.meterRegistry = meterRegistry;
    this.maxAttempts = maxAttempts;
  }

  public OrderResponse placeMarketOrder(Long contestId, Long userId, String idempotencyKey,
      PlaceOrderRequest request) {
    for (int attempt = 1; ; attempt++) {
      try {
        return orderService.placeMarketOrder(contestId, userId, idempotencyKey, request);
      } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
        String reason = (e instanceof OptimisticLockingFailureException) ? "optimistic" : "constraint";
        if (attempt >= maxAttempts) {
          meterRegistry.counter("orders.retry.exhausted", "reason", reason).increment();
          log.warn("주문 재시도 {}회 모두 실패({}): key={}", attempt, reason, idempotencyKey);
          throw e; // GlobalExceptionHandler 가 409 DATA_CONFLICT 로 바꾼다
        }
        meterRegistry.counter("orders.retry", "reason", reason).increment();
        backoff(attempt);
      }
    }
  }

  /**
   * 바로 다시 하면 같이 실패한 요청들과 또 부딪힌다. 시도할수록 조금 더, 그리고 무작위로 기다려
   * 다시 출발하는 시점을 흩어 놓는다(지터).
   */
  private static void backoff(int attempt) {
    long ms = ThreadLocalRandom.current().nextLong(5, 20) * attempt;
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("주문 재시도 대기 중 인터럽트", ie);
    }
  }
}
