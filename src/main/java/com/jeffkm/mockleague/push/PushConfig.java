package com.jeffkm.mockleague.push;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** league.push.enabled=true 면 Redis 로 발행한다. 끄면(기본) 아무것도 안 한다: 푸시 서버가 없는 환경에서 Redis 를 요구하지 않게. */
@Configuration
public class PushConfig {

  @Bean
  TickPublisher tickPublisher(@Value("${league.push.enabled:false}") boolean enabled,
      ObjectProvider<StringRedisTemplate> redis, MeterRegistry meters) {
    if (!enabled) {
      return (contestId, json) -> { };
    }
    return new RedisTickPublisher(redis.getObject(), meters);
  }
}
