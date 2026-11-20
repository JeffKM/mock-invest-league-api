package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 7주차① 서버가 두 대일 때 주문 속도 제한의 한도가 지켜지는가.
 * "서버 두 대" = 같은 저장소를 보는 제한기 객체 두 개. 요청은 로드밸런서처럼 번갈아 보낸다.
 */
@Testcontainers
class RateLimitAcrossServersTest {

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static final int BURST = 10;
  static final double NO_REFILL = 0.001; // 시험 중에는 토큰이 다시 차지 않게(1000초에 1개)

  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final List<LettuceConnectionFactory> factories = new ArrayList<>();
  StringRedisTemplate redis;

  @BeforeEach
  void setUp() {
    redis = template(REDIS.getHost(), REDIS.getMappedPort(6379));
    redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @AfterEach
  void tearDown() {
    factories.forEach(LettuceConnectionFactory::destroy);
  }

  StringRedisTemplate template(String host, int port) {
    var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build();
    var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
    factory.afterPropertiesSet();
    factory.start();
    factories.add(factory);
    return new StringRedisTemplate(factory);
  }

  RedisOrderRateLimiter redisServer(double perSecond, int burst) {
    return new RedisOrderRateLimiter(redis, perSecond, burst, new OrderRateLimiter(perSecond, burst, System::nanoTime),
        meters);
  }

  static int sendAlternately(UserRateLimiter a, UserRateLimiter b, long userId, int requests) {
    int accepted = 0;
    for (int i = 0; i < requests; i++) {
      if ((i % 2 == 0 ? a : b).tryAcquire(userId)) {
        accepted++;
      }
    }
    return accepted;
  }

  @Test
  void 재현_메모리_제한은_서버마다_따로_세서_두_대면_한도가_두_배가_된다() {
    var a = new OrderRateLimiter(NO_REFILL, BURST, () -> 0L);
    var b = new OrderRateLimiter(NO_REFILL, BURST, () -> 0L);

    assertThat(sendAlternately(a, b, 1L, 40)).isEqualTo(2 * BURST);
  }

  @Test
  void Redis_제한은_서버가_두_대여도_한도가_하나다() {
    var a = redisServer(NO_REFILL, BURST);
    var b = redisServer(NO_REFILL, BURST);

    assertThat(sendAlternately(a, b, 1L, 40)).isEqualTo(BURST);
  }

  @Test
  void 두_서버로_동시에_몰려도_정확히_burst_만큼만_받는다() throws Exception {
    var servers = List.of(redisServer(NO_REFILL, BURST), redisServer(NO_REFILL, BURST));
    int threads = 32;
    int requests = 200;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        UserRateLimiter server = servers.get(i % 2);
        futures.add(pool.submit(() -> {
          start.await();
          if (server.tryAcquire(7L)) {
            accepted.incrementAndGet();
          }
          return null;
        }));
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(accepted.get()).isEqualTo(BURST);
  }

  @Test
  void 사용자끼리는_서로_영향을_주지_않는다() {
    var a = redisServer(NO_REFILL, BURST);
    var b = redisServer(NO_REFILL, BURST);
    sendAlternately(a, b, 1L, 40);

    assertThat(a.tryAcquire(1L)).isFalse();
    assertThat(b.tryAcquire(2L)).isTrue();
  }

  @Test
  void 시간이_지나면_다시_찬다() throws Exception {
    var limiter = redisServer(20, 1); // 초당 20개 = 50 ms 에 1개
    assertThat(limiter.tryAcquire(1L)).isTrue();
    assertThat(limiter.tryAcquire(1L)).isFalse();

    Thread.sleep(150);

    assertThat(limiter.tryAcquire(1L)).isTrue();
  }

  @Test
  void 키에는_다_차는_시간만큼_만료가_걸린다() {
    redisServer(5, 10).tryAcquire(1L); // 빈 통이 차는 데 2초 + 여유 1초

    long ttl = redis.getExpire(RedisOrderRateLimiter.KEY_PREFIX + 1L, TimeUnit.MILLISECONDS);
    assertThat(ttl).isBetween(2_000L, 3_000L);
  }

  @Test
  void Redis_가_안_되면_서버별_제한으로_물러난다() {
    StringRedisTemplate dead = template("localhost", 1); // 아무도 듣지 않는 포트
    var limiter = new RedisOrderRateLimiter(dead, NO_REFILL, BURST,
        new OrderRateLimiter(NO_REFILL, BURST, () -> 0L), meters);

    int accepted = 0;
    for (int i = 0; i < 20; i++) {
      if (limiter.tryAcquire(1L)) {
        accepted++;
      }
    }

    assertThat(accepted).as("제한이 꺼지지도, 모두 막히지도 않는다").isEqualTo(BURST);
    assertThat(meters.counter("orders.rate_limit.fallback").count()).isEqualTo(20);
  }
}
