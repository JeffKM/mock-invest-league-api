package com.jeffkm.mockleague.order;

/**
 * 사용자별 주문 속도 제한의 창구(7주차①). 인터셉터는 기록이 서버 메모리(OrderRateLimiter)에 있는지
 * Redis(RedisOrderRateLimiter)에 있는지 모른다.
 */
public interface UserRateLimiter {

  /** 토큰이 있으면 하나 쓰고 true. 없으면 false(거절). */
  boolean tryAcquire(long userId);
}
