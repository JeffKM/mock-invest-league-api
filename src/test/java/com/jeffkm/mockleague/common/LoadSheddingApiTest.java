package com.jeffkm.mockleague.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 덜어내기(6주차⑨)를 켠 채 HTTP 로 확인한다. 자리는 1개, 기다리기 10 ms.
 * 테스트가 자리를 직접 하나 잡아 "꽉 찬 서버"를 만든다(MockMvc 는 요청을 한 스레드에서 차례로 처리하므로).
 */
@SpringBootTest(properties = {"league.shed.max-concurrent=1", "league.shed.max-wait=10ms"})
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class LoadSheddingApiTest {

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired LoadShedder shedder;

  private ApiFixture api;
  private long contestId;
  private long user;
  private boolean holding;

  @BeforeEach
  void setUp() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    api = new ApiFixture(mvc);
    contestId = api.createContest("2026-12-05", 7L, true);
    user = api.createUser();
    api.join(contestId, user);
  }

  @AfterEach
  void release() {
    if (holding) {
      shedder.exit();
    }
  }

  @Test
  void 요청이_끝나면_자리를_돌려준다() throws Exception {
    // 자리가 1개뿐인데 연달아 성공한다 = 앞 요청이 자리를 돌려줬다(성공·409 모두)
    for (int i = 0; i < 3; i++) {
      api.getAs(user, "/api/contests/{id}/me", contestId).andExpect(status().isOk());
    }
    api.marketOrder(contestId, user, "SBSI", "SELL", 1).andExpect(status().isConflict());   // 없는 주식 팔기 = 409
    api.getAs(user, "/api/contests/{id}/me", contestId).andExpect(status().isOk());
  }

  @Test
  void 자리가_꽉_차면_503과_Retry_After를_주고_DB까지_가지_않는다() throws Exception {
    holding = shedder.tryEnter();

    api.getAs(user, "/api/contests/{id}/me", contestId)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.error.code").value("SERVER_BUSY"));
    api.marketOrder(contestId, user, "SBSI", "BUY", 1).andExpect(status().isServiceUnavailable());

    shedder.exit();
    holding = false;
    api.getAs(user, "/api/contests/{id}/orders", contestId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));   // 덜어낸 주문은 만들어지지 않았다
  }

  @Test
  void 꽉_차도_관리자_API와_헬스_체크는_묶지_않는다() throws Exception {
    holding = shedder.tryEnter();

    mvc.perform(post("/api/admin/contests/{id}/ranking-snapshots", contestId)).andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
