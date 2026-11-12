package com.jeffkm.mockleague.support;

import com.jeffkm.mockleague.common.TimeConfig;
import java.time.LocalDateTime;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 통합 테스트용 시계 빈. @Primary 라서 운영용 clock 빈 대신 주입된다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

  /** 기본 시각: 2026-12-01(화) 10:00, 장 중. */
  public static final LocalDateTime MARKET_OPEN_TIME = LocalDateTime.of(2026, 12, 1, 10, 0);

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock(MARKET_OPEN_TIME, TimeConfig.KST);
  }
}
