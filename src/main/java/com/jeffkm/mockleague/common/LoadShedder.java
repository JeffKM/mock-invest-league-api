package com.jeffkm.mockleague.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 덜어내기(6주차⑨ P8). 동시에 처리 중인 API 요청 수를 maxConcurrent 로 묶는다.
 * 자리가 없으면 maxWait 만큼만 기다리고, 그래도 없으면 거절한다(호출하는 쪽이 503 + Retry-After 로 답한다).
 *
 * <p>왜: 넘치는 요청을 거절하지 않으면 톰캣 스레드 200개와 커넥션 대기열에 줄이 길어져 모든 요청이 늦어진다.
 * 12배 부하에서 서버는 초당 약 1,330건을 모두 200 으로 끝냈지만, 2초 안에 받은 것은 45% 뿐이었다(docs/07 12절).
 * 늦은 성공은 이미 떠난 클라이언트에게는 실패와 같고, 그 일 때문에 기다리던 다른 요청까지 늦어진다.
 * 넘치는 만큼을 바로 돌려보내면 받은 요청은 제시간에 끝난다.
 *
 * <p>공정한(fair) 세마포어: 먼저 기다린 요청이 먼저 자리를 얻는다. 기다리는 시간의 상한이 maxWait 로 정해진다.
 */
public class LoadShedder {

  private final Semaphore slots;
  private final long maxWaitNanos;
  private final Counter shed;

  public LoadShedder(int maxConcurrent, Duration maxWait, MeterRegistry meters) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent 는 1 이상이어야 합니다: " + maxConcurrent);
    }
    this.slots = new Semaphore(maxConcurrent, true);
    this.maxWaitNanos = maxWait.toNanos();
    this.shed = meters.counter("http.shed");
    Gauge.builder("http.inflight", slots, s -> maxConcurrent - s.availablePermits())
        .description("덜어내기 한도 안에서 처리 중인 API 요청 수").register(meters);
  }

  /** 자리를 얻으면 true. 반드시 {@link #exit()} 로 돌려준다. 못 얻으면 false(덜어낸 수를 센다). */
  public boolean tryEnter() {
    try {
      if (slots.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS)) {
        return true;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    shed.increment();
    return false;
  }

  public void exit() {
    slots.release();
  }
}
