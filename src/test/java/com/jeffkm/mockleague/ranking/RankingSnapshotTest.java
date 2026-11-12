package com.jeffkm.mockleague.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.account.Participant;
import com.jeffkm.mockleague.account.ParticipantRepository;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.ContestLifecycleService;
import com.jeffkm.mockleague.contest.ContestRepository;
import com.jeffkm.mockleague.contest.ContestStatus;
import com.jeffkm.mockleague.contest.TickPosition;
import com.jeffkm.mockleague.market.PriceTickRepository;
import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 순위 스냅샷(F12) 통합 테스트.
 * 09:00(0번 틱) 가격 = 상장 기준가라 손으로 계산할 수 있다. SBSI 84,000원, 수수료 0.5%.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class RankingSnapshotTest {

  private static final LocalDate DEC1 = LocalDate.of(2026, 12, 1);
  private static final long INITIAL = 10_000_000L;

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired ContestLifecycleService lifecycleService;
  @Autowired RankingService rankingService;
  @Autowired ContestRepository contestRepository;
  @Autowired ParticipantRepository participantRepository;
  @Autowired CompanyRepository companyRepository;
  @Autowired PriceTickRepository priceTickRepository;
  @Autowired JdbcTemplate jdbc;

  ApiFixture api;
  long contestId;
  long buyer;     // SBSI 10주 시장가 매수 → 수수료만큼 줄어든다
  long holder;    // 현금만
  long attendee;  // 출석 보너스

  @BeforeEach
  void setUp() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    api = new ApiFixture(mvc);
    contestId = api.createContest("2026-12-03", 42L, true);
    buyer = api.createUser();
    holder = api.createUser();
    attendee = api.createUser();
    for (long u : new long[] {buyer, holder, attendee}) {
      api.join(contestId, u);
    }
    api.marketOrder(contestId, buyer, "SBSI", "BUY", 10).andExpect(status().isOk());
    api.attend(contestId, attendee).andExpect(status().isCreated());
  }

  int snapshots() {
    return jdbc.queryForObject("select count(*) from ranking_snapshots where contest_id = ?", Integer.class, contestId);
  }

  @Test
  void 총자산은_현금_묶인_현금_보유_평가액의_합이고_큰_순서로_순위가_매겨진다() throws Exception {
    lifecycleService.snapshotRunning(LocalDateTime.now(clock));

    // 출석자 10,050,000 > 현금만 10,000,000 > 매수자 10,000,000 − 840,000 − 4,200 + 10 × 84,000 = 9,995,800
    api.fetch("/api/contests/{id}/rankings", contestId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tradingDay").value("2026-12-01"))
        .andExpect(jsonPath("$.data.tickNo").value(0))
        .andExpect(jsonPath("$.data.totalParticipants").value(3))
        .andExpect(jsonPath("$.data.entries[0].rank").value(1))
        .andExpect(jsonPath("$.data.entries[0].totalAssets").value(INITIAL + 50_000))
        .andExpect(jsonPath("$.data.entries[0].profit").value(50_000))
        .andExpect(jsonPath("$.data.entries[0].returnBp").value(50))      // 0.50%
        .andExpect(jsonPath("$.data.entries[1].totalAssets").value(INITIAL))
        .andExpect(jsonPath("$.data.entries[2].rank").value(3))
        .andExpect(jsonPath("$.data.entries[2].totalAssets").value(9_995_800))
        .andExpect(jsonPath("$.data.entries[2].returnBp").value(-5));     // −4,200 / 10,000,000 = −0.042% → 버림 −5bp
  }

  @Test
  void 지정가로_묶인_현금과_수량도_총자산에_들어간다() throws Exception {
    // 매수자가 SBSI 5주를 지정가 매도(수량 묶임), 현금만 가진 사람이 지정가 매수(현금 묶임)
    api.limitOrder(contestId, buyer, "SBSI", "SELL", 5, 100_000).andExpect(status().isOk());
    api.limitOrder(contestId, holder, "SBSI", "BUY", 10, 60_000).andExpect(status().isOk());

    lifecycleService.snapshotRunning(LocalDateTime.now(clock));

    api.getAs(buyer, "/api/contests/{id}/rankings/me", contestId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.me.totalAssets").value(9_995_800));
    api.getAs(holder, "/api/contests/{id}/rankings/me", contestId)
        .andExpect(jsonPath("$.data.me.totalAssets").value(INITIAL));
  }

  @Test
  void 같은_틱은_한_번만_찍고_조회는_새_스냅샷이_생길_때까지_그대로다() throws Exception {
    lifecycleService.snapshotRunning(LocalDateTime.now(clock));
    lifecycleService.snapshotRunning(LocalDateTime.now(clock));
    assertThat(snapshots()).isEqualTo(1);

    // 스냅샷 뒤에 현금만 가진 사람이 크게 사도 순위표는 그대로(조회는 스냅샷 기준)
    api.marketOrder(contestId, holder, "SBSI", "BUY", 100).andExpect(status().isOk());
    api.getAs(holder, "/api/contests/{id}/rankings/me", contestId)
        .andExpect(jsonPath("$.data.me.totalAssets").value(INITIAL));

    // 10:00(12번 틱)에 새로 찍으면 그 틱 가격으로 평가된다
    clock.set(DEC1.atTime(10, 0));
    lifecycleService.snapshotRunning(LocalDateTime.now(clock));
    assertThat(snapshots()).isEqualTo(2);

    long sbsiId = companyRepository.findByTicker("SBSI").orElseThrow().getId();
    long price12 = priceTickRepository.priceAt(contestId, sbsiId, DEC1, 12).orElseThrow();
    Participant b = participantRepository.findByContestIdAndUserId(contestId, buyer).orElseThrow();
    api.getAs(buyer, "/api/contests/{id}/rankings/me", contestId)
        .andExpect(jsonPath("$.data.tickNo").value(12))
        .andExpect(jsonPath("$.data.me.totalAssets").value(b.getCash() + 10 * price12));
  }

  @Test
  void I11_스냅샷은_미래_틱_가격을_쓰지_않는다() {
    // 하루치 가격(0~77틱)이 이미 DB 에 있어도 0번 틱 스냅샷은 0번 틱 가격(기준가)으로 평가한다
    rankingService.takeSnapshot(contestId, new TickPosition(DEC1, 0));
    Participant b = participantRepository.findByContestIdAndUserId(contestId, buyer).orElseThrow();
    Long total = jdbc.queryForObject(
        "select total_assets from ranking_entries where participant_id = ?", Long.class, b.getId());
    assertThat(total).isEqualTo(9_995_800L);
  }

  @Test
  void 총자산이_같으면_같은_순위다() throws Exception {
    long another = api.createUser();
    api.join(contestId, another); // 현금만 가진 사람이 둘
    lifecycleService.snapshotRunning(LocalDateTime.now(clock));

    api.fetch("/api/contests/{id}/rankings", contestId)
        .andExpect(jsonPath("$.data.entries[1].rank").value(2))
        .andExpect(jsonPath("$.data.entries[2].rank").value(2))
        .andExpect(jsonPath("$.data.entries[3].rank").value(4));
  }

  @Test
  void 대회가_끝나는_날_일일_배치가_최종_순위를_찍고_대회를_끝낸다() throws Exception {
    // 테스트 DB 는 테스트 클래스끼리 공유된다. runDaily 는 "진행 중인 모든 대회"의 가격을 만들므로,
    // 앞 테스트들이 남긴 대회까지 돌면 수십 초가 걸린다. 이 테스트의 대회만 남기고 나머지는 끝낸다
    jdbc.update("update contests set status = 'FINISHED' where status = 'RUNNING' and id <> ?", contestId);
    // 종료일(12/3) 다음 날 아침 배치: 12/2·12/3 가격이 필요하니 하루씩 배치를 돌려 만든다
    lifecycleService.runDaily(DEC1.plusDays(1));
    lifecycleService.runDaily(DEC1.plusDays(2));
    lifecycleService.runDaily(DEC1.plusDays(3));

    assertThat(contestRepository.findById(contestId).orElseThrow().getStatus()).isEqualTo(ContestStatus.FINISHED);
    clock.set(DEC1.plusDays(3).atTime(9, 0));
    api.fetch("/api/contests/{id}/rankings", contestId)
        .andExpect(jsonPath("$.data.tradingDay").value("2026-12-03"))
        .andExpect(jsonPath("$.data.tickNo").value(77))
        .andExpect(jsonPath("$.data.totalParticipants").value(3));
  }

  @Test
  void 스냅샷이_없거나_스냅샷_뒤에_참가했으면_내_순위는_비어_있고_비참가자는_404() throws Exception {
    api.getAs(buyer, "/api/contests/{id}/rankings/me", contestId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tradingDay").doesNotExist())
        .andExpect(jsonPath("$.data.me").doesNotExist());
    api.fetch("/api/contests/{id}/rankings", contestId)
        .andExpect(jsonPath("$.data.entries").isEmpty());

    lifecycleService.snapshotRunning(LocalDateTime.now(clock));
    long late = api.createUser();
    api.join(contestId, late);
    api.getAs(late, "/api/contests/{id}/rankings/me", contestId)
        .andExpect(jsonPath("$.data.totalParticipants").value(3))
        .andExpect(jsonPath("$.data.me").doesNotExist());

    long stranger = api.createUser();
    api.getAs(stranger, "/api/contests/{id}/rankings/me", contestId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_PARTICIPANT"));
  }

  // ---------- 정리 배치 ----------

  List<String> snapshotTicks() {
    return jdbc.queryForList("""
        select trading_day || ':' || tick_no from ranking_snapshots where contest_id = ?
         order by trading_day, tick_no
        """, String.class, contestId);
  }

  int entriesOfContest() {
    return jdbc.queryForObject("""
        select count(*) from ranking_entries e join ranking_snapshots s on s.id = e.snapshot_id
         where s.contest_id = ?
        """, Integer.class, contestId);
  }

  @Test
  void 정리_배치는_지난_날의_장중_스냅샷만_지우고_날마다_마지막_한_장과_오늘은_남긴다() throws Exception {
    // 12/1: 0, 12, 77틱 / 12/2: 0, 12틱
    lifecycleService.runDaily(DEC1.plusDays(1)); // 12/2 가격 만들기(이때 12/1 종가 스냅샷도 찍힌다)
    for (LocalDateTime t : List.of(DEC1.atTime(9, 0), DEC1.atTime(10, 0),
        DEC1.plusDays(1).atTime(9, 0), DEC1.plusDays(1).atTime(10, 0))) {
      lifecycleService.snapshotRunning(t);
    }
    assertThat(snapshotTicks()).containsExactly(
        "2026-12-01:0", "2026-12-01:12", "2026-12-01:77", "2026-12-02:0", "2026-12-02:12");

    assertThat(rankingService.pruneIntraday(contestId, DEC1.plusDays(1))).isEqualTo(2);
    assertThat(snapshotTicks()).containsExactly("2026-12-01:77", "2026-12-02:0", "2026-12-02:12");
    assertThat(entriesOfContest()).isEqualTo(3 * 3); // 남은 3장 × 참가자 3명

    // 다시 돌려도 지울 것이 없고, 순위 조회는 여전히 가장 최근(12/2 12틱)이다
    assertThat(rankingService.pruneIntraday(contestId, DEC1.plusDays(1))).isZero();
    clock.set(DEC1.plusDays(1).atTime(10, 0));
    api.fetch("/api/contests/{id}/rankings", contestId)
        .andExpect(jsonPath("$.data.tradingDay").value("2026-12-02"))
        .andExpect(jsonPath("$.data.tickNo").value(12));
  }

  @Test
  void 일일_배치가_정리까지_한다() {
    lifecycleService.snapshotRunning(DEC1.atTime(9, 0));
    lifecycleService.snapshotRunning(DEC1.atTime(10, 0));
    // 다른 테스트가 남긴 대회까지 배치가 돌지 않게(대회가 끝나는 날 테스트와 같은 이유)
    jdbc.update("update contests set status = 'FINISHED' where status = 'RUNNING' and id <> ?", contestId);
    lifecycleService.runDaily(DEC1.plusDays(1)); // 12/1 종가(77틱) 스냅샷 → 12/1 장중 두 장 삭제

    assertThat(snapshotTicks()).containsExactly("2026-12-01:77");
  }
}
