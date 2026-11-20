package com.jeffkm.mockleague.order;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 주문 속도 제한을 켠다(6주차③). league.order.rate-limit.per-second 가 0 이면 끈다(기본값: 꺼짐).
 * 운영(application-prod.yml)에서 켠다. 테스트는 꺼 둔다: 동시성 테스트는 한 사용자로 주문을 한꺼번에 보내 잠금을 시험하기 때문이다.
 *
 * <p>league.order.rate-limit.store: memory(기본, 서버 한 대) | redis(서버 여러 대, 7주차①).
 */
@Configuration
public class OrderRateLimitConfig implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(OrderRateLimitConfig.class);

  private final double perSecond;
  private final int burst;
  private final String store;
  private final MeterRegistry meters;
  private final ObjectProvider<StringRedisTemplate> redis;

  public OrderRateLimitConfig(
      @Value("${league.order.rate-limit.per-second:0}") double perSecond,
      @Value("${league.order.rate-limit.burst:10}") int burst,
      @Value("${league.order.rate-limit.store:memory}") String store,
      MeterRegistry meters,
      ObjectProvider<StringRedisTemplate> redis) {
    if (!store.equals("memory") && !store.equals("redis")) {
      throw new IllegalArgumentException("league.order.rate-limit.store 는 memory 또는 redis 입니다: " + store);
    }
    this.perSecond = perSecond;
    this.burst = burst;
    this.store = store;
    this.meters = meters;
    this.redis = redis;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    if (perSecond <= 0) {
      return;
    }
    log.info("주문 속도 제한: 사용자당 초당 {}건, 한 번에 최대 {}건, 저장소 {}", perSecond, burst, store);
    UserRateLimiter memory = new OrderRateLimiter(perSecond, burst, System::nanoTime);
    UserRateLimiter limiter = store.equals("redis")
        ? new RedisOrderRateLimiter(redis.getObject(), perSecond, burst, memory, meters)
        : memory;
    registry.addInterceptor(new OrderRateLimitInterceptor(limiter, meters))
        .addPathPatterns("/api/contests/*/orders");
  }
}
