package com.jeffkm.mockleague.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 측정용 시계(고정·가속)만 본다. 실제 시간은 가짜(밀리초)로 움직인다. */
class TimeConfigTest {

  private static final LocalDateTime OPEN = LocalDateTime.parse("2026-11-16T10:00:00");

  @Test
  void speed_가_없으면_고정_시계다() {
    Clock clock = new TimeConfig().clock("2026-11-16T10:00:00", 0);
    assertThat(LocalDateTime.now(clock)).isEqualTo(OPEN);
  }

  @Test
  void 가속_시계는_기준_시각부터_speed_배로_흐른다() {
    AtomicLong real = new AtomicLong(1_000_000L);
    Clock clock = new TimeConfig.AcceleratedClock(OPEN.atZone(TimeConfig.KST).toInstant(), 1_000_000L, 5,
        real::get, TimeConfig.KST);

    assertThat(LocalDateTime.now(clock)).isEqualTo(OPEN);
    real.addAndGet(60_000L); // 실제 60초 → 5분(틱 하나)
    assertThat(LocalDateTime.now(clock)).isEqualTo(OPEN.plusMinutes(5));
  }

  @Test
  void 기준_시각은_지금이거나_그_이전인_가장_가까운_30초다() {
    long minute = 1_800_000_000_000L - 1_800_000_000_000L % 60_000L; // 어떤 분의 0초
    assertThat(TimeConfig.anchorAtHalfMinute(minute + 45_000L)).isEqualTo(minute + 30_000L);
    assertThat(TimeConfig.anchorAtHalfMinute(minute + 30_000L)).isEqualTo(minute + 30_000L);
    assertThat(TimeConfig.anchorAtHalfMinute(minute + 10_000L)).isEqualTo(minute - 30_000L);
  }
}
