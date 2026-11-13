package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 주문 접수(POST /api/contests/{id}/orders) 앞에서 사용자별 속도 제한을 건다.
 * 컨트롤러·트랜잭션보다 먼저 돌기 때문에, 거절된 요청은 DB 커넥션을 잡지 않는다(이것이 핵심이다).
 * 사용자 헤더가 없거나 숫자가 아니면 그냥 통과시킨다. 컨트롤러가 400 으로 답한다.
 */
public class OrderRateLimitInterceptor implements HandlerInterceptor {

  static final String USER_HEADER = "X-User-Id";

  private final OrderRateLimiter limiter;
  private final Counter rejected;

  public OrderRateLimitInterceptor(OrderRateLimiter limiter, MeterRegistry meters) {
    this.limiter = limiter;
    this.rejected = meters.counter("orders.rate_limited");
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!"POST".equals(request.getMethod())) {
      return true;
    }
    Long userId = parse(request.getHeader(USER_HEADER));
    if (userId == null || limiter.tryAcquire(userId)) {
      return true;
    }
    rejected.increment();
    throw new BusinessException(ErrorCode.RATE_LIMITED); // GlobalExceptionHandler 가 429 + Retry-After 로 바꾼다
  }

  private static Long parse(String value) {
    try {
      return value == null ? null : Long.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
