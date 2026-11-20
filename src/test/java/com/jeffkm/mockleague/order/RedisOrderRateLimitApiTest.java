package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.junit.jupiter.api.Test;
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

/** 7주차① store=redis 설정으로 띄우면 HTTP 주문의 속도 제한 기록이 Redis 에 생기고, 한도를 넘으면 429 다. */
@SpringBootTest(properties = {
    "league.order.rate-limit.per-second=0.001",
    "league.order.rate-limit.burst=3",
    "league.order.rate-limit.store=redis"})
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class, RedisOrderRateLimitApiTest.RedisConfig.class})
class RedisOrderRateLimitApiTest {

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
  @Autowired StringRedisTemplate redis;

  @Test
  void 한도를_넘은_주문은_429_이고_기록은_Redis_에_있다() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    ApiFixture api = new ApiFixture(mvc);
    long contestId = api.createContest("2026-12-05", 7L, true);
    long user = api.createUser();
    api.join(contestId, user);

    for (int i = 0; i < 3; i++) {
      api.marketOrder(contestId, user, "SBSI", "BUY", 1).andExpect(status().isOk());
    }
    api.marketOrder(contestId, user, "SBSI", "BUY", 1)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

    assertThat(redis.hasKey(RedisOrderRateLimiter.KEY_PREFIX + user)).isTrue();
  }
}
