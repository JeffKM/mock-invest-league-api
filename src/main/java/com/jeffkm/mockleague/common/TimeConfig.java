package com.jeffkm.mockleague.common;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
   * 밤이나 주말에도 Swagger 로 주문을 눌러 보기 위한 로컬 전용 설정이다. 비워 두면 실제 시계.
   */
  @Bean
  public Clock clock(@Value("${league.clock.fixed:}") String fixed) {
    if (fixed.isBlank()) {
      return Clock.system(KST);
    }
    return Clock.fixed(LocalDateTime.parse(fixed).atZone(KST).toInstant(), KST);
  }
}
