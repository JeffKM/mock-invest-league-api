package com.jeffkm.mockleague.contest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jeffkm.mockleague.contest.InvariantAuditService.Check;
import com.jeffkm.mockleague.contest.InvariantAuditService.Report;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 불변식 검사(6주차③). 정상 거래 뒤에는 모든 검사가 0건이고, 장부를 일부러 한 곳 망가뜨리면 그 검사가 잡아내는지 본다.
 * "검사가 늘 0을 돌려주는 고장"도 통과로 보이므로, 잡아내는 쪽도 반드시 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class InvariantAuditTest {

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired InvariantAuditService auditService;
  @Autowired JdbcTemplate jdbc;

  private ApiFixture api;
  private long contestId;
  private long userId;

  @BeforeEach
  void setUp() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    api = new ApiFixture(mvc);
    contestId = api.createContest("2026-12-05", 7L, true);
    userId = api.createUser();
    api.join(contestId, userId);

    // 시장가 매수·매도, 대기 중인 지정가 매수·매도, 취소까지 한 번씩 거친다
    api.marketOrder(contestId, userId, "SBSI", "BUY", 10).andExpect(status().isOk());
    api.marketOrder(contestId, userId, "SBSI", "SELL", 3).andExpect(status().isOk());
    api.limitOrder(contestId, userId, "SBSI", "SELL", 2, 999_999_999L).andExpect(status().isOk());
    api.limitOrder(contestId, userId, "ARCL", "BUY", 5, 1_000L).andExpect(status().isOk());
    String res = api.limitOrder(contestId, userId, "ARCL", "BUY", 1, 1_000L)
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    long toCancel = ((Number) JsonPath.read(res, "$.data.orderId")).longValue();
    mvc.perform(delete("/api/contests/{c}/orders/{o}", contestId, toCancel).header("X-User-Id", userId))
        .andExpect(status().isOk());
  }

  @Test
  void 정상_거래_뒤에는_모든_검사가_0건() throws Exception {
    Report report = auditService.audit(contestId);

    assertThat(report.checks()).extracting(Check::violations).containsOnly(0L);
    assertThat(report.ok()).isTrue();
    api.fetch("/api/admin/contests/{id}/invariants", contestId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ok").value(true))
        .andExpect(jsonPath("$.data.checks.length()").value(7)); // 7번째: 순위 줄 참조(V5)
  }

  @Test
  void 현금이_장부와_1원만_달라도_잡는다() {
    jdbc.update("update participants set cash = cash + 1 where contest_id = ? and user_id = ?", contestId, userId);

    assertThat(violations(auditService.audit(contestId), "I5_CASH_EQUALS_LEDGER")).isEqualTo(1);
  }

  @Test
  void 같은_주문이_원장에_두_번_기록되면_잡는다() {
    // 동시성 버그로 체결 처리가 두 번 돈 상황을 흉내 낸다: 거래 한 줄이 더 들어가고 현금도 그만큼 빠졌다
    Long orderId = jdbc.queryForObject("""
        select o.id from orders o join participants p on p.id = o.participant_id
        where p.contest_id = ? and o.status = 'FILLED' and o.side = 'BUY' limit 1""", Long.class, contestId);
    jdbc.update("""
        insert into cash_ledger (participant_id, entry_type, amount, order_id)
        select participant_id, entry_type, amount, order_id from cash_ledger where order_id = ? and entry_type = 'BUY'""",
        orderId);

    Report report = auditService.audit(contestId);
    assertThat(violations(report, "ORDER_LEDGER_ONCE")).isEqualTo(1);
    assertThat(violations(report, "I5_CASH_EQUALS_LEDGER")).isEqualTo(1); // 현금은 그대로인데 원장만 늘었다
    assertThat(report.ok()).isFalse();
  }

  @Test
  void 묶인_돈이나_주식이_대기_주문과_다르면_잡는다() {
    // 취소했는데 묶인 돈이 안 풀린 상황(원장과의 합계는 그대로라 I5 로는 안 보인다)
    jdbc.update("update participants set cash = cash - 1000, reserved_cash = reserved_cash + 1000 "
        + "where contest_id = ? and user_id = ?", contestId, userId);
    jdbc.update("update holdings set reserved_quantity = reserved_quantity + 1 where participant_id = "
        + "(select id from participants where contest_id = ? and user_id = ?)", contestId, userId);

    Report report = auditService.audit(contestId);
    assertThat(violations(report, "I5_CASH_EQUALS_LEDGER")).isZero();
    assertThat(violations(report, "RESERVED_CASH_EQUALS_PENDING_BUYS")).isEqualTo(1);
    assertThat(violations(report, "RESERVED_QTY_EQUALS_PENDING_SELLS")).isEqualTo(1);
  }

  @Test
  void 보유_수량이_체결_내역과_다르면_잡는다() {
    jdbc.update("update holdings set quantity = quantity + 1 where participant_id = "
        + "(select id from participants where contest_id = ? and user_id = ?)", contestId, userId);

    assertThat(violations(auditService.audit(contestId), "HOLDING_EQUALS_FILLED_NET")).isEqualTo(1);
  }

  @Test
  void 체결_정보가_상태와_어긋나면_잡는다() {
    jdbc.update("""
        update orders set filled_price = 1 where id = (select o.id from orders o
          join participants p on p.id = o.participant_id where p.contest_id = ? and o.status = 'CANCELED')""",
        contestId);

    assertThat(violations(auditService.audit(contestId), "I10_FILL_FIELDS_MATCH_STATUS")).isEqualTo(1);
  }

  @Test
  void 순위_줄이_없는_참가자를_가리키면_잡는다() {
    // 외래 키를 뺐으므로(V5) DB 가 막지 않는다. 바로 그 빈자리를 검사가 메우는지 본다
    Long snapshotId = jdbc.queryForObject("""
        insert into ranking_snapshots (contest_id, trading_day, tick_no) values (?, date '2026-12-01', 77)
        returning id""", Long.class, contestId);
    Long participantId = jdbc.queryForObject(
        "select id from participants where contest_id = ? and user_id = ?", Long.class, contestId, userId);
    jdbc.update("insert into ranking_entries (snapshot_id, participant_id, rank, total_assets) values (?, ?, 1, 1)",
        snapshotId, participantId);
    assertThat(violations(auditService.audit(contestId), "RANKING_ENTRY_REFS")).isZero();

    jdbc.update("insert into ranking_entries (snapshot_id, participant_id, rank, total_assets) values (?, ?, 2, 1)",
        snapshotId, 987_654_321L);

    assertThat(violations(auditService.audit(contestId), "RANKING_ENTRY_REFS")).isEqualTo(1);
  }

  private static long violations(Report report, String code) {
    return report.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().violations();
  }
}
