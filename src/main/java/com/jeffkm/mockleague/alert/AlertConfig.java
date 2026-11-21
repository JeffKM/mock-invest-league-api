package com.jeffkm.mockleague.alert;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 알림 창구를 고른다(7주차②). league.alert.discord-webhook-url 이 비어 있으면 로그로만 남긴다(로컬·테스트).
 */
@Configuration
public class AlertConfig {

  private static final Logger log = LoggerFactory.getLogger(AlertConfig.class);

  @Bean
  AlertNotifier alertNotifier(
      @Value("${league.alert.discord-webhook-url:}") String webhookUrl,
      @Value("${league.alert.timeout:3s}") Duration timeout,
      @Value("${league.alert.max-in-flight:4}") int maxInFlight,
      MeterRegistry meters) {
    if (webhookUrl.isBlank()) {
      log.info("알림: 웹훅 주소가 없어 로그로만 남긴다");
      return text -> log.warn("[알림] {}", text);
    }
    log.info("알림: Discord 웹훅, 시간 제한 {}, 동시 전송 최대 {}", timeout, maxInFlight);
    // 전송 결과 처리는 데몬 스레드 2개면 충분하다(보내기 자체는 HttpClient 의 비동기 입출력이 한다)
    var executor = Executors.newFixedThreadPool(2, r -> {
      Thread t = new Thread(r, "league-alert");
      t.setDaemon(true);
      return t;
    });
    return new DiscordAlertNotifier(URI.create(webhookUrl), timeout, maxInFlight, executor, meters);
  }
}
