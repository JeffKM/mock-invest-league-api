package com.jeffkm.leaguepush;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

/**
 * 본 API 가 보낸 Redis 채널 league:v1:push:contest:{id} 를 구독해 그 대회의 방송으로 넘긴다.
 * Redis 가 끊기면 1초부터 30초까지 늘려 가며 다시 붙는다(그동안 온 메시지는 pub/sub 특성상 없다. 다음 틱에 새로 온다).
 */
@Component
public class RedisRelay implements SmartLifecycle {

  /** 본 API 의 RedisTickPublisher.CHANNEL_PREFIX 와 같아야 한다. */
  static final String CHANNEL_PREFIX = "league:v1:push:contest:";
  private static final Logger log = LoggerFactory.getLogger(RedisRelay.class);

  private final ReactiveRedisConnectionFactory factory;
  private final ContestStreams streams;
  private ReactiveRedisMessageListenerContainer container;
  private Disposable subscription;

  public RedisRelay(ReactiveRedisConnectionFactory factory, ContestStreams streams) {
    this.factory = factory;
    this.streams = streams;
  }

  @Override
  public void start() {
    container = new ReactiveRedisMessageListenerContainer(factory);
    subscription = container.receive(PatternTopic.of(CHANNEL_PREFIX + "*"))
        .doOnNext(message -> {
          Long contestId = contestId(message.getChannel());
          if (contestId != null) {
            streams.publish(contestId, message.getMessage());
          }
        })
        .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
            .doBeforeRetry(r -> log.warn("Redis 구독이 끊겨 다시 붙습니다({}번째): {}", r.totalRetries() + 1,
                r.failure().getClass().getSimpleName())))
        .subscribe();
  }

  static Long contestId(String channel) {
    try {
      return Long.parseLong(channel.substring(CHANNEL_PREFIX.length()));
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public void stop() {
    if (subscription != null) {
      subscription.dispose();
    }
    if (container != null) {
      container.destroy();
    }
  }

  @Override
  public boolean isRunning() {
    return subscription != null && !subscription.isDisposed();
  }
}
