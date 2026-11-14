package com.jeffkm.mockleague.contest;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 6주차⑦ P6: 대회를 시작하면 상장 표(contest_listings)의 통계를 새로 고친다.
 * 통계가 낡으면 플래너가 새 대회의 종목 수를 1개로 예상해 순위 스냅샷에 나쁜 계획을 고른다(docs/07 10절).
 * pg_class.reltuples(플래너가 아는 줄 수)가 실제 줄 수와 같아졌는지로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class ContestStatisticsTest {

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired JdbcTemplate jdbc;

  @Test
  void 대회를_시작하면_상장_표의_통계가_실제_줄_수와_맞는다() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    ApiFixture api = new ApiFixture(mvc);
    api.createContest("2026-12-31", 1L, false);
    api.createContest("2026-12-31", 2L, false);
    long started = api.createContest("2026-12-31", 3L, true);

    long actual = jdbc.queryForObject("select count(*) from contest_listings", Long.class);
    double planned = jdbc.queryForObject("select reltuples from pg_class where relname = 'contest_listings'", Double.class);

    assertThat(started).isPositive();
    assertThat((long) planned).isEqualTo(actual);
  }
}
