package com.jeffkm.mockleague.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 데우기(6주차⑥): 진짜 포트로 자기 자신에게 조회를 보내고, 모두 200 이어야 한다. readiness 가 헬스에 들어가는지도 본다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"league.warmup.enabled=true", "league.warmup.duration=1s"})
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class WarmupRunnerTest {

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired WarmupRunner warmup;
  @Autowired HealthEndpoint health;

  @Test
  void 진행_중인_대회의_참가자로_조회를_보내고_모두_200_이다() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    ApiFixture api = new ApiFixture(mvc);
    long contestId = api.createContest("2026-12-31", 9L, true);
    long user = api.createUser();
    api.join(contestId, user);

    WarmupRunner.Result result = warmup.warmUp();

    assertThat(result.requests()).isPositive(); // 개수는 기계 속도에 달렸다(바쁜 맥에서 1초에 4건). 보는 것은 "보냈고 모두 200"
    assertThat(result.byStatus().keySet()).containsOnly(200);
  }

  @Test
  void readiness_가_헬스_검사에_들어간다() {
    // 데우기 중에는 이것이 OUT_OF_SERVICE 라 /actuator/health 전체가 UP 이 아니다 → 도커 헬스 체크가 기다린다
    CompositeHealth root = (CompositeHealth) health.health();
    assertThat(root.getComponents()).containsKey("readinessState");
  }
}
