package com.jeffkm.mockleague.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 테스트용 시계. Clock.fixed 는 한 번 정하면 못 바꾸므로, 테스트 중간에 "장 마감 후"로 옮길 수 있게 만든다.
 */
public class MutableClock extends Clock {

  private final ZoneId zone;
  private Instant instant;

  public MutableClock(LocalDateTime start, ZoneId zone) {
    this.zone = zone;
    set(start);
  }

  public void set(LocalDateTime time) {
    this.instant = time.atZone(zone).toInstant();
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("테스트에서는 시간대를 바꾸지 않는다");
  }

  @Override
  public Instant instant() {
    return instant;
  }
}
