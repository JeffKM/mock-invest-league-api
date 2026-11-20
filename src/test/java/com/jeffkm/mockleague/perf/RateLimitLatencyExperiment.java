package com.jeffkm.mockleague.perf;

import com.jeffkm.mockleague.order.OrderRateLimiter;
import com.jeffkm.mockleague.order.RedisOrderRateLimiter;
import com.jeffkm.mockleague.order.UserRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * 7주차① 속도 제한 한 번에 드는 시간: 서버 메모리 vs Redis(Lua). 주문 한 건마다 한 번 부른다.
 * 실행: LEAGUE_EXPERIMENT=true ./run_tests.sh --tests '*RateLimitLatencyExperiment*' -i
 */
@EnabledIfEnvironmentVariable(named = "LEAGUE_EXPERIMENT", matches = "true")
class RateLimitLatencyExperiment {

  static final int WARMUP = 5_000;
  static final int RUNS = 20_000;
  static final int USERS = 1_000;

  @Test
  void 메모리와_Redis_의_한_번_비용() {
    try (GenericContainer<?> container = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
      container.start();
      var factory = new LettuceConnectionFactory(
          new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
      factory.afterPropertiesSet();
      factory.start();
      try {
        var redis = new StringRedisTemplate(factory);
        UserRateLimiter memory = new OrderRateLimiter(1_000_000, 1_000_000, System::nanoTime);
        UserRateLimiter remote = new RedisOrderRateLimiter(redis, 1_000_000, 1_000_000,
            new OrderRateLimiter(1, 1, System::nanoTime), new SimpleMeterRegistry());
        print("memory", measure(memory));
        print("redis", measure(remote));
      } finally {
        factory.destroy();
      }
    }
  }

  static long[] measure(UserRateLimiter limiter) {
    for (int i = 0; i < WARMUP; i++) {
      limiter.tryAcquire(i % USERS);
    }
    long[] nanos = new long[RUNS];
    for (int i = 0; i < RUNS; i++) {
      long t = System.nanoTime();
      limiter.tryAcquire(i % USERS);
      nanos[i] = System.nanoTime() - t;
    }
    Arrays.sort(nanos);
    return nanos;
  }

  static void print(String name, long[] sorted) {
    System.out.printf("[rate-limit] %-6s p50 %.1f us  p99 %.1f us  max %.1f us%n", name,
        sorted[sorted.length / 2] / 1e3, sorted[(int) (sorted.length * 0.99)] / 1e3, sorted[sorted.length - 1] / 1e3);
  }
}
