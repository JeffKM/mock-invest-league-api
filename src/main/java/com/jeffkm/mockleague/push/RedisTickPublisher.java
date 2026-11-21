package com.jeffkm.mockleague.push;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis pub/sub 채널 league:v1:push:contest:{id} 로 보낸다. 푸시 서버가 몇 대든 모두 같은 메시지를 받는다.
 * pub/sub 은 받는 쪽이 없으면 버린다(저장하지 않는다). 시세는 다음 틱에 새로 오므로 그래도 된다.
 * Redis 가 죽으면 세고 넘어간다(스냅샷은 그대로 끝난다).
 */
public class RedisTickPublisher implements TickPublisher {

  public static final String CHANNEL_PREFIX = "league:v1:push:contest:";
  private static final Logger log = LoggerFactory.getLogger(RedisTickPublisher.class);

  private final StringRedisTemplate redis;
  private final Counter sent;
  private final Counter failed;

  public RedisTickPublisher(StringRedisTemplate redis, MeterRegistry meters) {
    this.redis = redis;
    this.sent = meters.counter("push.publish", "result", "sent");
    this.failed = meters.counter("push.publish", "result", "failed");
  }

  @Override
  public void publish(long contestId, String json) {
    try {
      redis.convertAndSend(CHANNEL_PREFIX + contestId, json);
      sent.increment();
    } catch (DataAccessException e) {
      failed.increment();
      log.warn("푸시 발행 실패: contest={} ({})", contestId, e.getClass().getSimpleName());
    }
  }
}
