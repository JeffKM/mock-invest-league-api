package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 주문 속도 제한(6주차③)을 켠 채 HTTP 로 확인한다. 한도: 한 번에 3건, 다시 차는 속도는 1,000초에 1건(테스트가 느려도 그사이 다시 차지 않게).
 * 핵심은 "거절된 요청은 주문을 만들지 않는다"와 "다른 사용자·조회는 막지 않는다"이다.
 */
@SpringBootTest(properties = {"league.order.rate-limit.per-second=0.001", "league.order.rate-limit.burst=3"})
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class OrderRateLimitApiTest {

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired OrderRepository orderRepository;

  private ApiFixture api;
  private long contestId;

  @BeforeEach
  void setUp() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    api = new ApiFixture(mvc);
    contestId = api.createContest("2026-12-05", 7L, true);
  }

  @Test
  void 한도를_넘은_주문은_429_와_Retry_After_를_받고_주문이_만들어지지_않는다() throws Exception {
    long user = api.createUser();
    api.join(contestId, user);
    long before = orderRepository.count();

    for (int i = 0; i < 3; i++) {
      api.marketOrder(contestId, user, "SBSI", "BUY", 1).andExpect(status().isOk());
    }
    api.marketOrder(contestId, user, "SBSI", "BUY", 1)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

    assertThat(orderRepository.count() - before).isEqualTo(3);
  }

  @Test
  void 한_사람이_한도에_걸려도_다른_사람의_주문과_조회는_막지_않는다() throws Exception {
    long bot = api.createUser();
    long person = api.createUser();
    api.join(contestId, bot);
    api.join(contestId, person);
    for (int i = 0; i < 4; i++) {
      api.marketOrder(contestId, bot, "SBSI", "BUY", 1);
    }

    api.marketOrder(contestId, person, "SBSI", "BUY", 1).andExpect(status().isOk());
    api.getAs(bot, "/api/contests/{id}/me", contestId).andExpect(status().isOk());
    api.getAs(bot, "/api/contests/{id}/orders", contestId).andExpect(status().isOk());
  }
}
