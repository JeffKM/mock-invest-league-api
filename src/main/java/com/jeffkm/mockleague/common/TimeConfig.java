package com.jeffkm.mockleague.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각은 반드시 이 Clock 빈으로 얻는다(LocalDateTime.now() 직접 호출 금지).
 * 테스트에서 Clock.fixed(...)로 바꿔 "장 시간 안/밖", "출석 날짜"를 재현하기 위함.
 */
@Configuration
public class TimeConfig {

  public static final ZoneId KST = ZoneId.of("Asia/Seoul");

  @Bean
  public Clock clock() {
    return Clock.system(KST);
  }
}
