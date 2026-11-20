package com.jeffkm.mockleague.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 서버가 여러 대여도 한도가 하나로 지켜지는 주문 속도 제한(7주차①). 토큰 버킷 규칙은 OrderRateLimiter 와 같고,
 * 통을 서버 메모리 대신 Redis 에 둔다.
 *
 * <p>정합성을 지키는 세 가지:
 * <ul>
 *   <li><b>읽기·계산·쓰기를 Lua 스크립트 하나로.</b> Redis 는 스크립트를 한 번에 하나씩 끝까지 실행한다. GET 하고 앱에서 계산해
 *       SET 하면 두 서버가 같은 잔량을 읽고 각자 1 을 빼는 갱신 손실이 생긴다(주문의 계좌 잔고와 같은 문제, ROADMAP 14단계).</li>
 *   <li><b>시각은 Redis 의 TIME.</b> 서버마다 시계가 조금씩 다르다. 앱 시각을 넘기면 시계가 빠른 서버가 토큰을 더 채운다.</li>
 *   <li><b>만료 = 다 차는 데 걸리는 시간.</b> 그 뒤의 통은 어차피 가득이라, 키가 사라져도 결과가 같다. 쉬는 사용자의 키는 저절로 지워진다.</li>
 * </ul>
 *
 * <p>Redis 가 안 되면 서버별 메모리 제한(fallback)으로 물러난다. 한도가 서버 대수만큼 느슨해지지만, 제한이 아예 꺼지거나
 * 모든 주문을 막는 것보다 낫다. 로그는 상태가 바뀔 때만 한 줄 남긴다(요청마다 남기면 로그가 병목이 된다, ROADMAP 21단계).
 */
public class RedisOrderRateLimiter implements UserRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RedisOrderRateLimiter.class);

  static final String KEY_PREFIX = "league:v1:rl:order:";

  // ts 는 마이크로초(약 1.7e15). Lua 의 tostring 은 유효숫자 14자리라 잘리므로 %.0f 로 쓴다
  private static final String SCRIPT = """
      local rate = tonumber(ARGV[1])
      local burst = tonumber(ARGV[2])
      local t = redis.call('TIME')
      local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
      local v = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
      local tokens = tonumber(v[1])
      local ts = tonumber(v[2])
      if tokens == nil or ts == nil then
        tokens = burst
        ts = now
      end
      tokens = math.min(burst, tokens + math.max(0, now - ts) / 1000000 * rate)
      local ok = 0
      if tokens >= 1 then
        tokens = tokens - 1
        ok = 1
      end
      redis.call('HSET', KEYS[1], 'tokens', string.format('%.6f', tokens), 'ts', string.format('%.0f', now))
      redis.call('PEXPIRE', KEYS[1], ARGV[3])
      return ok
      """;

  private final StringRedisTemplate redis;
  private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(SCRIPT, Long.class);
  private final String perSecond;
  private final String burst;
  private final String ttlMillis;
  private final UserRateLimiter fallback;
  private final Counter fallbackCount;
  private final AtomicBoolean degraded = new AtomicBoolean(false);

  public RedisOrderRateLimiter(StringRedisTemplate redis, double perSecond, int burst, UserRateLimiter fallback,
      MeterRegistry meters) {
    if (perSecond <= 0 || burst < 1) {
      throw new IllegalArgumentException("perSecond 는 0 보다, burst 는 1 이상이어야 합니다");
    }
    this.redis = redis;
    this.perSecond = Double.toString(perSecond);
    this.burst = Integer.toString(burst);
    // 빈 통이 가득 차는 시간 + 여유 1초
    this.ttlMillis = Long.toString((long) Math.ceil(burst / perSecond * 1000) + 1000);
    this.fallback = fallback;
    this.fallbackCount = meters.counter("orders.rate_limit.fallback");
  }

  @Override
  public boolean tryAcquire(long userId) {
    Long ok;
    try {
      ok = redis.execute(script, List.of(KEY_PREFIX + userId), perSecond, burst, ttlMillis);
    } catch (DataAccessException e) {
      fallbackCount.increment();
      if (degraded.compareAndSet(false, true)) {
        log.warn("주문 속도 제한: Redis 를 쓸 수 없어 서버별 제한으로 물러남 ({})", e.getClass().getSimpleName());
      }
      return fallback.tryAcquire(userId);
    }
    if (degraded.compareAndSet(true, false)) {
      log.info("주문 속도 제한: Redis 로 돌아옴");
    }
    return ok != null && ok == 1L;
  }
}
