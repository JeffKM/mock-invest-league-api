package com.jeffkm.mockleague.order;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 사용자별 주문 속도 제한(6주차③). 토큰 버킷: 사용자마다 통에 토큰이 최대 burst 개 있고, 초당 perSecond 개씩 다시 찬다.
 * 주문 하나가 토큰 하나를 쓰고, 통이 비면 거절한다.
 *
 * <p>왜 필요한가(docs/07 4·5절): 주문은 계좌 행을 잠근 채 처리된다. 한 계좌에 초당 100건이 몰리면 요청들이 잠금 앞에
 * 줄을 서는데, 줄을 선 동안 각자 DB 커넥션을 쥐고 있다. 그때 서버가 잠깐만 멈춰도(측정에서는 매분 AWS 가 CPU 를 가져갔다)
 * 줄이 커넥션 풀 전체로 번졌다. 사람이 낼 수 없는 속도의 주문을 커넥션을 잡기 전에 돌려보내면 줄이 길어질 수 없다.
 *
 * <p>한계: 기록이 이 서버의 메모리에 있다. 서버가 여러 대면 각자 따로 세므로 실제 한도가 대수만큼 커진다.
 * 그때는 Redis 같은 공용 저장소에 둔다.
 */
public class OrderRateLimiter {

  /** 이 시간 동안 주문이 없던 사용자의 기록은 지운다(메모리가 사용자 수만큼 계속 늘지 않게). */
  private static final long IDLE_NANOS = 60_000_000_000L;
  private static final int SWEEP_EVERY = 10_000;

  private final double perSecond;
  private final double burst;
  private final LongSupplier nanoTime;
  private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();
  private final AtomicLong calls = new AtomicLong();

  public OrderRateLimiter(double perSecond, int burst, LongSupplier nanoTime) {
    if (perSecond <= 0 || burst < 1) {
      throw new IllegalArgumentException("perSecond 는 0 보다, burst 는 1 이상이어야 합니다");
    }
    this.perSecond = perSecond;
    this.burst = burst;
    this.nanoTime = nanoTime;
  }

  /** 토큰이 있으면 하나 쓰고 true. 없으면 false(거절). */
  public boolean tryAcquire(long userId) {
    long now = nanoTime.getAsLong();
    if (calls.incrementAndGet() % SWEEP_EVERY == 0) {
      buckets.values().removeIf(b -> b.idleLongerThan(now, IDLE_NANOS));
    }
    return buckets.computeIfAbsent(userId, id -> new Bucket(burst, now)).tryTake(now, perSecond, burst);
  }

  int trackedUsers() {
    return buckets.size();
  }

  /** 사용자 한 명의 통. 같은 사용자의 동시 요청끼리만 이 잠금을 다투고, 다른 사용자와는 다투지 않는다. */
  private static final class Bucket {
    private double tokens;
    private long last;

    Bucket(double tokens, long now) {
      this.tokens = tokens;
      this.last = now;
    }

    synchronized boolean tryTake(long now, double perSecond, double burst) {
      tokens = Math.min(burst, tokens + (now - last) / 1e9 * perSecond);
      last = now;
      if (tokens < 1) {
        return false;
      }
      tokens -= 1;
      return true;
    }

    synchronized boolean idleLongerThan(long now, long idle) {
      return now - last > idle;
    }
  }
}
