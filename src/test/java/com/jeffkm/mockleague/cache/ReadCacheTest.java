package com.jeffkm.mockleague.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.common.ReadCache;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.ContestLifecycleService;
import com.jeffkm.mockleague.market.PriceGenerationService;
import com.jeffkm.mockleague.market.PriceTickRepository;
import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 조회 캐시(서버 메모리)가 맞는 값만 들고 있는지. 키에 버전(틱, 스냅샷 id)을 넣었으니 무효화 없이도 새 값이 보여야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class ReadCacheTest {

  private static final LocalDate DEC1 = LocalDate.of(2026, 12, 1);

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired MeterRegistry meters;
  @Autowired PriceGenerationService priceGenerationService;
  @Autowired PriceTickRepository priceTickRepository;
  @Autowired CompanyRepository companyRepository;
  @Autowired ContestLifecycleService lifecycleService;

  ApiFixture api;
  long contestId;
  long userId;

  @BeforeEach
  void setUp() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    api = new ApiFixture(mvc);
    contestId = api.createContest("2026-12-31", 42L, true);
    userId = api.createUser();
    api.join(contestId, userId);
    api.marketOrder(contestId, userId, "SBSI", "BUY", 10).andExpect(status().isOk());
  }

  double count(String cache, String result) {
    var c = meters.find("league.cache").tag("cache", cache).tag("result", result).counter();
    return c == null ? 0 : c.count();
  }

  long sbsiPrice(LocalDate day, int tick) {
    long id = companyRepository.findByTicker("SBSI").orElseThrow().getId();
    return priceTickRepository.priceAt(contestId, id, day, tick).orElseThrow();
  }

  @Test
  void 같은_틱이면_두_번째부터_캐시에서_읽고_틱이_바뀌면_새_가격이다() throws Exception {
    clock.set(DEC1.atTime(10, 0)); // 12번 틱
    double hits = count(ReadCache.LATEST_PRICES, "hit");
    api.getAs(userId, "/api/contests/{id}/me", contestId)
        .andExpect(jsonPath("$.data.holdings[0].currentPrice").value(sbsiPrice(DEC1, 12)));
    api.getAs(userId, "/api/contests/{id}/me", contestId)
        .andExpect(jsonPath("$.data.holdings[0].currentPrice").value(sbsiPrice(DEC1, 12)));
    assertThat(count(ReadCache.LATEST_PRICES, "hit")).isEqualTo(hits + 1);

    clock.set(DEC1.atTime(10, 5)); // 13번 틱: 키가 달라져 새로 읽는다(무효화 없이)
    api.getAs(userId, "/api/contests/{id}/me", contestId)
        .andExpect(jsonPath("$.data.holdings[0].currentPrice").value(sbsiPrice(DEC1, 13)));
  }

  @Test
  void 그_틱의_가격이_아직_없으면_캐시하지_않아_가격이_생긴_뒤_바로_보인다() throws Exception {
    // 12월 2일 가격을 아직 안 만든 상태(배치가 밀림): 지금 틱 대신 12월 1일 종가가 보인다
    clock.set(LocalDateTime.of(2026, 12, 2, 10, 0));
    api.getAs(userId, "/api/contests/{id}/me", contestId)
        .andExpect(jsonPath("$.data.holdings[0].currentPrice").value(sbsiPrice(DEC1, 77)));

    // 가격이 생기면 같은 시각·같은 키라도 새 가격이 보여야 한다(예전 값이 캐시에 남아 있으면 안 된다)
    priceGenerationService.ensureGeneratedThrough(contestId, DEC1.plusDays(1));
    api.getAs(userId, "/api/contests/{id}/me", contestId)
        .andExpect(jsonPath("$.data.holdings[0].currentPrice").value(sbsiPrice(DEC1.plusDays(1), 12)));
  }

  @Test
  void 순위표는_스냅샷_id로_캐시해_새_스냅샷이_생기면_바로_바뀐다() throws Exception {
    lifecycleService.snapshotRunning(LocalDateTime.now(clock));
    double hits = count(ReadCache.RANKING_TOP, "hit");
    api.fetch("/api/contests/{id}/rankings", contestId).andExpect(jsonPath("$.data.tickNo").value(0));
    api.fetch("/api/contests/{id}/rankings", contestId).andExpect(jsonPath("$.data.tickNo").value(0));
    assertThat(count(ReadCache.RANKING_TOP, "hit")).isEqualTo(hits + 1);

    clock.set(DEC1.atTime(10, 0));
    lifecycleService.snapshotRunning(LocalDateTime.now(clock));
    api.fetch("/api/contests/{id}/rankings", contestId)
        .andExpect(jsonPath("$.data.tickNo").value(12))
        .andExpect(jsonPath("$.data.entries[0].totalAssets").value(
            10_000_000L - 840_000 - 4_200 + 10 * sbsiPrice(DEC1, 12)));
  }
}
