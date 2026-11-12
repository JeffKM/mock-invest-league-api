package com.jeffkm.mockleague.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.common.ReadCache;
import com.jeffkm.mockleague.market.PriceProvider;
import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/** 캐시 저장소를 Redis 로 바꿔도 같은 값이 나오는지, Redis 가 죽어도 요청이 실패하지 않는지. */
@SpringBootTest(properties = "spring.cache.type=redis")
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class, RedisReadCacheTest.RedisConfig.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisReadCacheTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class RedisConfig {
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
      return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired MeterRegistry meters;
  @Autowired PriceProvider priceProvider;
  @Autowired StringRedisTemplate redis;
  @Autowired GenericContainer<?> redisContainer;

  double count(String result) {
    var c = meters.find("league.cache").tag("cache", ReadCache.LATEST_PRICES).tag("result", result).counter();
    return c == null ? 0 : c.count();
  }

  long newContest() throws Exception {
    clock.set(LocalDate.of(2026, 12, 1).atTime(10, 0));
    return new ApiFixture(mvc).createContest("2026-12-31", 42L, true);
  }

  @Test
  @Order(1)
  void Redis에_저장했다_꺼내도_DB에서_읽은_값과_같다() throws Exception {
    long contestId = newContest();
    double hits = count("hit");
    Map<Long, Long> first = priceProvider.currentPrices(contestId);   // DB → Redis
    Map<Long, Long> second = priceProvider.currentPrices(contestId);  // Redis → 역직렬화
    assertThat(second).isEqualTo(first).hasSize(24);
    assertThat(count("hit")).isEqualTo(hits + 1);
    assertThat(redis.keys("league:v1:latestPrices::" + contestId + ":*")).hasSize(1);
  }

  @Test
  @Order(2)
  void Redis가_멈춰도_DB로_읽어_요청은_성공한다() throws Exception {
    long contestId = newContest();
    Map<Long, Long> expected = priceProvider.currentPrices(contestId);
    redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
    try {
      double errors = count("error");
      long start = System.nanoTime();
      assertThat(priceProvider.currentPrices(contestId)).isEqualTo(expected);
      long ms = (System.nanoTime() - start) / 1_000_000;
      assertThat(count("error")).isGreaterThan(errors);
      assertThat(ms).as("timeout 500ms 근처에서 포기하고 DB 로 넘어간다").isLessThan(3_000);
    } finally {
      redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
    }
  }
}
