package com.jeffkm.mockleague.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각은 반드시 이 Clock 빈으로 얻는다(LocalDateTime.now() 직접 호출 금지).
 * 테스트에서 Clock.fixed(...)로 바꿔 "장 시간 안/밖", "출석 날짜"를 재현하기 위함.
 */
@Configuration
public class TimeConfig {

  public static final ZoneId KST = ZoneId.of("Asia/Seoul");

  /**
   * league.clock.fixed 에 시각(예: 2026-12-01T10:00:00)을 주면 서버 시계를 그 시각에 고정한다.
   * 밤이나 주말에도 주문을 눌러 보거나 부하를 걸기 위한 측정·로컬 전용 설정이다. 비워 두면 실제 시계.
   *
   * <p>league.clock.speed 를 함께 주면(예: 5) 고정하지 않고 그 배속으로 흐르게 한다(6주차⑤). 틱 경계(5분)마다
   * 캐시 키가 바뀌는 순간을 부하 테스트 안에서 여러 번 만들기 위해서다. 5배속이면 틱이 실제 60초마다 바뀐다.
   */
  @Bean
  public Clock clock(@Value("${league.clock.fixed:}") String fixed, @Value("${league.clock.speed:0}") double speed) {
    if (fixed.isBlank()) {
      return Clock.system(KST);
    }
    Instant start = LocalDateTime.parse(fixed).atZone(KST).toInstant();
    if (speed <= 0) {
      return Clock.fixed(start, KST);
    }
    return new AcceleratedClock(start, anchorAtHalfMinute(System.currentTimeMillis()), speed,
        System::currentTimeMillis, KST);
  }

  /**
   * 가속 시계가 fixed 시각을 가리키는 실제 시각. 지금이거나 그 이전인 가장 가까운 "xx분 30초"다.
   * fixed 가 틱 경계(예: 10:00:00)이고 5배속이면 틱 경계가 실제 매분 30초에 온다. 매분 0초 무렵의
   * CPU steal(docs/07 5절)과 겹치지 않게 반 분 비켜 둔 것이다. k6 의 tick 시나리오도 30초에 맞춰 시작한다.
   */
  static long anchorAtHalfMinute(long nowMillis) {
    long half = Math.floorDiv(nowMillis, 60_000L) * 60_000L + 30_000L;
    return half <= nowMillis ? half : half - 60_000L;
  }

  /** 실제 시각 anchorMillis 에 start 를 가리키고, 그 뒤로 실제 시간의 speed 배로 흐르는 시계. */
  static final class AcceleratedClock extends Clock {
    private final Instant start;
    private final long anchorMillis;
    private final double speed;
    private final LongSupplier realMillis;
    private final ZoneId zone;

    AcceleratedClock(Instant start, long anchorMillis, double speed, LongSupplier realMillis, ZoneId zone) {
      this.start = start;
      this.anchorMillis = anchorMillis;
      this.speed = speed;
      this.realMillis = realMillis;
      this.zone = zone;
    }

    @Override
    public Instant instant() {
      return start.plusMillis(Math.round((realMillis.getAsLong() - anchorMillis) * speed));
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new AcceleratedClock(start, anchorMillis, speed, realMillis, zone);
    }
  }
}
