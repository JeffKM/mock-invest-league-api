package com.jeffkm.mockleague.common;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 덜어내기를 켠다(6주차⑨). league.shed.max-concurrent 가 0 이면(기본) 이 설정 자체가 등록되지 않는다. 운영(application-prod.yml)에서 켠다.
 * 관리자 API(정산·스냅샷처럼 오래 걸리는 운영 작업)와 actuator(헬스 체크·지표)는 묶지 않는다:
 * 몰릴 때일수록 상태를 보고 손을 쓸 수 있어야 한다. 12배 부하에서 Prometheus 가 지표를 못 가져간 일이 있었다.
 */
@Configuration
@ConditionalOnExpression("${league.shed.max-concurrent:0} > 0")
public class LoadSheddingConfig implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(LoadSheddingConfig.class);

  private final LoadShedder shedder;

  public LoadSheddingConfig(@Value("${league.shed.max-concurrent}") int maxConcurrent,
      @Value("${league.shed.max-wait:500ms}") Duration maxWait, MeterRegistry meters) {
    this.shedder = new LoadShedder(maxConcurrent, maxWait, meters);
    log.info("덜어내기: 동시 처리 {}건, 자리 기다리기 최대 {}", maxConcurrent, maxWait);
  }

  @Bean
  LoadShedder loadShedder() {
    return shedder;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // order 1: 주문 속도 제한(기본 0)이 먼저 돈다. 속도를 넘은 요청이 자리를 차지하지 않게
    registry.addInterceptor(new LoadSheddingInterceptor(shedder))
        .addPathPatterns("/api/**").excludePathPatterns("/api/admin/**").order(1);
  }
}
