package com.jeffkm.mockleague.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jeffkm.mockleague.company.Company;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestRepository;
import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 가상 공시(F6)와 미래 공시 숨김(I11) 통합 테스트. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class DisclosureIntegrationTest {

  private static final LocalDate DEC1 = LocalDate.of(2026, 12, 1);

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired PriceGenerationService priceGenerationService;
  @Autowired PriceTickRepository priceTickRepository;
  @Autowired ContestRepository contestRepository;
  @Autowired CompanyRepository companyRepository;
  @Autowired JdbcTemplate jdbc;

  ApiFixture api;

  @BeforeEach
  void setUp() {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    api = new ApiFixture(mvc);
  }

  /** DB 를 거치지 않고 planner 로 미리 계산한 그날의 공시(정답지). */
  List<DisclosurePlanner.Planned> expectedPlans(long seed, LocalDate day) {
    List<DisclosurePlanner.Listed> listed = companyRepository.findAll().stream()
        .map(c -> new DisclosurePlanner.Listed(c.getId(), c.getTicker(), c.getName(), c.getSector()))
        .toList();
    return DisclosurePlanner.planDay(seed, day, 78, listed);
  }

  /** 첫날에 "회사 공시"가 하나 이상 있는 시드를 찾는다(시드마다 공시가 0~2건이라). */
  long seedWithCompanyDisclosureOnDec1() {
    for (long seed = 1; seed < 1_000; seed++) {
      if (expectedPlans(seed, DEC1).stream().anyMatch(p -> p.scope() == DisclosureScope.COMPANY)) {
        return seed;
      }
    }
    throw new IllegalStateException("시드를 찾지 못했다");
  }

  int disclosureRows(long contestId) {
    return jdbc.queryForObject("select count(*) from disclosures where contest_id = ?", Integer.class, contestId);
  }

  @Test
  void 가격을_만든_날마다_planner_와_같은_공시가_저장되고_다시_만들어도_늘지_않는다() throws Exception {
    long seed = 42L;
    long contestId = api.createContest("2026-12-31", seed, true);
    priceGenerationService.ensureGeneratedThrough(contestId, DEC1.plusDays(9));

    int expected = 0;
    for (int d = 0; d < 10; d++) {
      expected += expectedPlans(seed, DEC1.plusDays(d)).size();
    }
    assertThat(disclosureRows(contestId)).isEqualTo(expected);

    // 같은 날까지 다시 돌리거나, 이미 만든 날의 공시를 다시 넣어도 그대로다(유니크 + ON CONFLICT)
    priceGenerationService.ensureGeneratedThrough(contestId, DEC1.plusDays(9));
    new DisclosureRepository(jdbc).insertDay(contestId, DEC1, expectedPlans(seed, DEC1));
    assertThat(disclosureRows(contestId)).isEqualTo(expected);
  }

  @Test
  void 회사_공시의_충격이_그_종목_가격에_들어간다() throws Exception {
    long seed = seedWithCompanyDisclosureOnDec1();
    long contestId = api.createContest("2026-12-31", seed, true);
    DisclosurePlanner.Planned plan = expectedPlans(seed, DEC1).stream()
        .filter(p -> p.scope() == DisclosureScope.COMPANY).findFirst().orElseThrow();
    Company company = companyRepository.findById(plan.companyId()).orElseThrow();
    Contest contest = contestRepository.findById(contestId).orElseThrow();

    // 같은 시드의 "충격 없는 경로"와 비교: 공시 틱 바로 앞까지는 같고, 공시 틱에서 갈라진다
    long[] plain = PriceEngine.generateDay(seed, company.getTicker(), DEC1, company.getBasePrice(),
        company.getVolatility(), 78, contest.getPriceLimitBp());
    long before = priceTickRepository.priceAt(contestId, company.getId(), DEC1, plan.tick() - 1).orElseThrow();
    long at = priceTickRepository.priceAt(contestId, company.getId(), DEC1, plan.tick()).orElseThrow();
    assertThat(before).isEqualTo(plain[plan.tick() - 1]);
    assertThat(at).isNotEqualTo(plain[plan.tick()]);
    // 그날 경로 전체가 "그날 공시들의 충격을 넣은 엔진 결과"와 정확히 같다
    DisclosurePlanner.Listed listed = new DisclosurePlanner.Listed(company.getId(), company.getTicker(),
        company.getName(), company.getSector());
    long[] shocked = PriceEngine.generateDay(seed, company.getTicker(), DEC1, company.getBasePrice(),
        company.getVolatility(), 78, contest.getPriceLimitBp(),
        DisclosurePlanner.shocksFor(listed, expectedPlans(seed, DEC1), 78));
    for (int t = 0; t < 78; t++) {
      assertThat(priceTickRepository.priceAt(contestId, company.getId(), DEC1, t)).contains(shocked[t]);
    }
  }

  @Test
  void I11_공시_틱_전에는_안_보이고_그_틱부터_보인다() throws Exception {
    long seed = seedWithCompanyDisclosureOnDec1();
    long contestId = api.createContest("2026-12-31", seed, true);
    DisclosurePlanner.Planned plan = expectedPlans(seed, DEC1).stream()
        .filter(p -> p.scope() == DisclosureScope.COMPANY).findFirst().orElseThrow();
    Company company = companyRepository.findById(plan.companyId()).orElseThrow();
    // 공시 틱이 시작하는 시각(09:00 + 틱 × 5분)
    var publishedAt = DEC1.atTime(9, 0).plusMinutes(5L * plan.tick());

    clock.set(publishedAt.minusSeconds(1));
    String before = api.fetch("/api/contests/{id}/disclosures", contestId)
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    List<String> titlesBefore = JsonPath.read(before, "$.data[*].title");
    assertThat(titlesBefore).doesNotContain(plan.title());

    clock.set(publishedAt);
    api.fetch("/api/contests/{id}/disclosures", contestId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].title").value(plan.title()))
        .andExpect(jsonPath("$.data[0].scope").value("COMPANY"))
        .andExpect(jsonPath("$.data[0].ticker").value(company.getTicker()))
        .andExpect(jsonPath("$.data[0].tickNo").value(plan.tick()))
        .andExpect(jsonPath("$.data[0].impactBp").doesNotExist()); // 충격 크기(정답)는 내보내지 않는다
  }

  @Test
  void 다음_날_공시는_오늘_장_마감_후에도_안_보인다() throws Exception {
    long contestId = api.createContest("2026-12-31", 42L, true);
    priceGenerationService.ensureGeneratedThrough(contestId, DEC1.plusDays(1)); // 내일 공시도 이미 DB 에 있다
    clock.set(DEC1.atTime(23, 0));

    String body = api.fetch("/api/contests/{id}/disclosures?limit=100", contestId)
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    List<String> days = JsonPath.read(body, "$.data[*].tradingDay");
    assertThat(days).allMatch("2026-12-01"::equals);
    assertThat(days).hasSize(expectedPlans(42L, DEC1).size());
  }

  @Test
  void 대회_시작_전에는_빈_목록이고_limit_범위를_벗어나면_400() throws Exception {
    long contestId = api.createContest("2026-12-31", 42L, true);
    clock.set(DEC1.atTime(8, 0));
    api.fetch("/api/contests/{id}/disclosures", contestId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());

    api.fetch("/api/contests/{id}/disclosures?limit=0", contestId).andExpect(status().isBadRequest());
    api.fetch("/api/contests/{id}/disclosures?limit=101", contestId).andExpect(status().isBadRequest());
  }

  @Test
  void 없는_대회는_404() throws Exception {
    api.fetch("/api/contests/{id}/disclosures", 999_999).andExpect(status().isNotFound());
  }
}
