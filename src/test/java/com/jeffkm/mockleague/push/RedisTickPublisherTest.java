package com.jeffkm.mockleague.push;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;

/** 7주차⑤ 발행은 Redis 채널로 가고, Redis 가 없어도 던지지 않고 실패로 센다. */
class RedisTickPublisherTest {

  static LettuceConnectionFactory factory(String host, int port) {
    var f = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
    f.afterPropertiesSet();
    f.start();
    return f;
  }

  @Test
  void 대회_채널로_발행하면_구독한_쪽이_받는다() throws Exception {
    try (GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
      redis.start();
      var f = factory(redis.getHost(), redis.getMappedPort(6379));
      var listener = new RedisMessageListenerContainer();
      try {
        BlockingQueue<String> got = new ArrayBlockingQueue<>(4);
        listener.setConnectionFactory(f);
        listener.addMessageListener((message, pattern) -> got.add(new String(message.getBody())),
            new ChannelTopic(RedisTickPublisher.CHANNEL_PREFIX + 7));
        listener.afterPropertiesSet();
        listener.start();
        TimeUnit.MILLISECONDS.sleep(300);   // 구독이 붙을 시간(pub/sub 은 붙기 전 메시지를 버린다)

        var meters = new SimpleMeterRegistry();
        new RedisTickPublisher(new StringRedisTemplate(f), meters).publish(7, "{\"tick\":3}");

        assertThat(got.poll(5, TimeUnit.SECONDS)).isEqualTo("{\"tick\":3}");
        assertThat(meters.counter("push.publish", "result", "sent").count()).isEqualTo(1);
      } finally {
        listener.destroy();
        f.destroy();
      }
    }
  }

  @Test
  void Redis_가_없으면_던지지_않고_실패로_센다() {
    var f = factory("127.0.0.1", 1);
    try {
      var meters = new SimpleMeterRegistry();
      new RedisTickPublisher(new StringRedisTemplate(f), meters).publish(7, "{}");
      assertThat(meters.counter("push.publish", "result", "failed").count()).isEqualTo(1);
    } finally {
      f.destroy();
    }
  }
}
