package com.jeffkm.mockleague.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.account.AccountService;
import com.jeffkm.mockleague.company.Company;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.ContestLifecycleService;
import com.jeffkm.mockleague.contest.ContestRepository;
import com.jeffkm.mockleague.contest.ContestService;
import com.jeffkm.mockleague.contest.ContestStatus;
import com.jeffkm.mockleague.contest.dto.CreateContestRequest;
import com.jeffkm.mockleague.order.OrderFacade;
import com.jeffkm.mockleague.order.OrderSide;
import com.jeffkm.mockleague.order.dto.OrderResponse;
import com.jeffkm.mockleague.order.dto.PlaceOrderRequest;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import com.jeffkm.mockleague.user.UserService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 가격 생성·조회·일일 배치를 실제 DB 로 확인한다. */
@SpringBootTest
@Import({PostgresTestConfig.class, TestClockConfig.class})
class PriceTickIntegrationTest {

  private static final LocalDate DEC1 = LocalDate.of(2026, 12, 1);

  @Autowired ContestService contestService;
  @Autowired ContestLifecycleService lifecycleService;
  @Autowired ContestRepository contestRepository;
  @Autowired PriceGenerationService priceGenerationService;
  @Autowired PriceProvider priceProvider;
  @Autowired PriceTickRepository priceTickRepository;
  @Autowired CompanyRepository companyRepository;
  @Autowired UserService userService;
  @Autowired AccountService accountService;
  @Autowired OrderFacade orderFacade;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;

  Company sbsi;

  @BeforeEach
  void setUp() {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    sbsi = companyRepository.findByTicker("SBSI").orElseThrow();
  }

  long newContest(LocalDate starts, LocalDate ends, long seed) {
    return contestService.create(new CreateContestRequest("가격", starts, ends, seed)).id();
  }

  int rows(long contestId) {
    return jdbc.queryForObject("select count(*) from price_ticks where contest_id = ?", Integer.class, contestId);
  }

  @Test
  void 대회를_시작하면_첫날_가격_24종목_78틱이_생긴다() {
    long id = newContest(DEC1, DEC1.plusDays(30), 42L);
    contestService.start(id);
    assertThat(rows(id)).isEqualTo(24 * 78);
    assertThat(priceTickRepository.lastGeneratedDay(id)).contains(DEC1);
  }

  @Test
  void 같은_날까지_다시_생성해도_행이_늘지_않는다() {
    long id = newContest(DEC1, DEC1.plusDays(30), 42L);
    contestService.start(id);
    assertThat(priceGenerationService.ensureGeneratedThrough(id, DEC1)).isZero();
    assertThat(rows(id)).isEqualTo(24 * 78);
  }

  @Test
  void 다음_날_시가는_전날_종가이고_같은_시드의_두_대회는_가격이_같다() {
    long a = newContest(DEC1, DEC1.plusDays(30), 7L);
    long b = newContest(DEC1, DEC1.plusDays(30), 7L);
    priceGenerationService.ensureGeneratedThrough(a, DEC1.plusDays(2));
    priceGenerationService.ensureGeneratedThrough(b, DEC1.plusDays(2));

    long close1 = priceTickRepository.closesOf(a, DEC1).get(sbsi.getId());
    assertThat(priceTickRepository.priceAt(a, sbsi.getId(), DEC1.plusDays(1), 0)).contains(close1);
    for (int tick : new int[] {0, 30, 77}) {
      assertThat(priceTickRepository.priceAt(b, sbsi.getId(), DEC1.plusDays(2), tick))
          .isEqualTo(priceTickRepository.priceAt(a, sbsi.getId(), DEC1.plusDays(2), tick));
    }
  }

  @Test
  void 가격은_종료일을_넘어서_만들지_않는다() {
    long id = newContest(DEC1, DEC1.plusDays(1), 42L);
    assertThat(priceGenerationService.ensureGeneratedThrough(id, DEC1.plusDays(10))).isEqualTo(2);
  }

  @Test
  void I11_현재가는_지금_틱의_가격이고_미래_가격은_섞이지_않는다() {
    long id = newContest(DEC1, DEC1.plusDays(30), 42L);
    contestService.start(id);
    priceGenerationService.ensureGeneratedThrough(id, DEC1.plusDays(1)); // 내일 가격까지 이미 DB 에 있다

    clock.set(DEC1.atTime(10, 0)); // 12번 틱
    long tick12 = priceTickRepository.priceAt(id, sbsi.getId(), DEC1, 12).orElseThrow();
    assertThat(priceProvider.currentPrices(id)).containsEntry(sbsi.getId(), tick12);

    clock.set(DEC1.atTime(20, 0)); // 장 마감 후: 오늘 종가(내일 가격이 아니라)
    long close = priceTickRepository.closesOf(id, DEC1).get(sbsi.getId());
    assertThat(priceProvider.currentPrices(id)).containsEntry(sbsi.getId(), close);
  }

  @Test
  void 시장가_주문은_주문한_틱의_가격으로_체결된다() {
    long id = newContest(DEC1, DEC1.plusDays(30), 42L);
    contestService.start(id);
    long userId = userService.create("p-" + UUID.randomUUID().toString().substring(0, 8)).id();
    accountService.join(id, userId);

    clock.set(DEC1.atTime(11, 7)); // (2시간 7분) / 5분 = 25번 틱
    OrderResponse res = orderFacade.placeMarketOrder(id, userId, "k",
        new PlaceOrderRequest("SBSI", OrderSide.BUY, 1));
    assertThat(res.filledPrice()).isEqualTo(priceTickRepository.priceAt(id, sbsi.getId(), DEC1, 25).orElseThrow());
  }

  @Test
  void 일일_배치는_시작일이_된_대회를_시작하고_내일_가격을_만들고_끝난_대회를_닫는다() {
    long scheduled = newContest(DEC1.plusDays(1), DEC1.plusDays(5), 1L);
    long ending = newContest(DEC1, DEC1, 2L);
    contestService.start(ending);

    lifecycleService.runDaily(DEC1.plusDays(1));

    assertThat(contestRepository.findById(scheduled).orElseThrow().getStatus()).isEqualTo(ContestStatus.RUNNING);
    assertThat(priceTickRepository.lastGeneratedDay(scheduled)).as("오늘과 내일").contains(DEC1.plusDays(2));
    assertThat(contestRepository.findById(ending).orElseThrow().getStatus()).isEqualTo(ContestStatus.FINISHED);

    // 두 번 돌려도 같다
    lifecycleService.runDaily(DEC1.plusDays(1));
    assertThat(rows(scheduled)).isEqualTo(24 * 78 * 2);
  }
}
