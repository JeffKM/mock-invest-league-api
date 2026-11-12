package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jeffkm.mockleague.account.CashLedgerRepository;
import com.jeffkm.mockleague.account.Participant;
import com.jeffkm.mockleague.account.ParticipantRepository;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 시장가 주문 통합 테스트: HTTP 요청 → 컨트롤러 → 서비스(트랜잭션) → 실제 PostgreSQL.
 * 테스트마다 새 사용자·새 대회를 만들어 서로 데이터가 섞이지 않게 한다(롤백에 기대지 않는다:
 * 테스트에 @Transactional 을 붙이면 서비스 트랜잭션이 테스트 트랜잭션에 합류해 실제 커밋·롤백을 검증할 수 없다).
 *
 * 1주차 임시 현재가 = 상장 기준가. SBSI 84,000원, ARCL 52,000원. 수수료 0.5%(원 미만 버림).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class MarketOrderApiTest {

  private static final long INITIAL = Contest.DEFAULT_INITIAL_CASH; // 10,000,000

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired ParticipantRepository participantRepository;
  @Autowired OrderRepository orderRepository;
  @Autowired CashLedgerRepository ledgerRepository;

  private long userId;
  private long contestId;

  @BeforeEach
  void setUp() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    userId = createUser();
    contestId = createContest(true);
    join(contestId, userId).andExpect(status().isCreated());
  }

  // ---------- 참가 ----------

  @Test
  void 참가하면_초기_자금과_INITIAL_원장_한_줄이_생긴다() {
    Participant p = participant();
    assertThat(p.getCash()).isEqualTo(INITIAL);
    assertThat(ledgerRepository.sumAmountByParticipantId(p.getId())).isEqualTo(INITIAL); // I5 출발점
  }

  @Test
  void 같은_대회에_두_번_참가하면_409() throws Exception {
    join(contestId, userId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ALREADY_JOINED"));
  }

  // ---------- 매수·매도 ----------

  @Test
  void 매수하면_현금은_금액과_수수료만큼_줄고_보유가_생긴다() throws Exception {
    order("k-1", "SBSI", "BUY", 10)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("FILLED"))
        .andExpect(jsonPath("$.data.filledPrice").value(84_000))
        .andExpect(jsonPath("$.data.amount").value(840_000))
        .andExpect(jsonPath("$.data.fee").value(4_200));

    assertThat(participant().getCash()).isEqualTo(INITIAL - 840_000 - 4_200);
    me().andExpect(jsonPath("$.data.holdings[0].ticker").value("SBSI"))
        .andExpect(jsonPath("$.data.holdings[0].quantity").value(10))
        .andExpect(jsonPath("$.data.holdings[0].averagePrice").value(84_000));
  }

  @Test
  void I1_잔고보다_큰_매수는_409이고_아무것도_바뀌지_않는다() throws Exception {
    order("k-big", "SBSI", "BUY", 200) // 16,800,000원 > 10,000,000원
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CASH"));

    Participant p = participant();
    assertThat(p.getCash()).isEqualTo(INITIAL);
    assertThat(orderRepository.findByParticipantIdAndIdempotencyKey(p.getId(), "k-big")).isEmpty();
    me().andExpect(jsonPath("$.data.holdings").isEmpty());
  }

  @Test
  void I2_가진_것보다_많이_팔면_409이고_보유는_그대로다() throws Exception {
    order("k-1", "SBSI", "BUY", 5).andExpect(status().isOk());
    long cashBefore = participant().getCash();

    order("k-2", "SBSI", "SELL", 6)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_QUANTITY"));
    order("k-3", "ARCL", "SELL", 1) // 한 번도 산 적 없는 종목
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_QUANTITY"));

    assertThat(participant().getCash()).isEqualTo(cashBefore);
    me().andExpect(jsonPath("$.data.holdings[0].quantity").value(5));
  }

  @Test
  void 매도하면_금액에서_수수료를_뺀_만큼_현금이_늘어난다() throws Exception {
    order("k-1", "SBSI", "BUY", 10).andExpect(status().isOk());
    long cashBefore = participant().getCash();

    order("k-2", "SBSI", "SELL", 4) // 336,000원, 수수료 1,680원
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fee").value(1_680));

    assertThat(participant().getCash()).isEqualTo(cashBefore + 336_000 - 1_680);
    me().andExpect(jsonPath("$.data.holdings[0].quantity").value(6));
  }

  @Test
  void I5_여러_번_사고판_뒤에도_현금은_원장_합계와_같다() throws Exception {
    order("t1", "SBSI", "BUY", 10).andExpect(status().isOk());
    order("t2", "ARCL", "BUY", 7).andExpect(status().isOk());
    order("t3", "SBSI", "SELL", 3).andExpect(status().isOk());
    order("t4", "ARCL", "SELL", 7).andExpect(status().isOk()); // 전량 매도
    order("t5", "SBSI", "BUY", 1).andExpect(status().isOk());
    order("t6", "SBSI", "BUY", 1_000).andExpect(status().isConflict()); // 실패한 주문은 원장에 흔적이 없어야 한다

    Participant p = participant();
    assertThat(p.getCash() + p.getReservedCash())
        .isEqualTo(ledgerRepository.sumAmountByParticipantId(p.getId()));
  }

  @Test
  void 내_계좌의_총자산은_현금과_보유_평가액의_합이다() throws Exception {
    order("k-1", "SBSI", "BUY", 10).andExpect(status().isOk());

    // 가격이 움직이지 않으므로(1주차) 총자산 = 초기 자금 − 낸 수수료
    me().andExpect(jsonPath("$.data.cash").value(INITIAL - 844_200))
        .andExpect(jsonPath("$.data.holdingsValue").value(840_000))
        .andExpect(jsonPath("$.data.totalAssets").value(INITIAL - 4_200))
        .andExpect(jsonPath("$.data.holdings[0].unrealizedProfit").value(0));
  }

  // ---------- 멱등성(I6) ----------

  @Test
  void I6_같은_키로_두_번_보내면_한_번만_실행되고_같은_응답을_받는다() throws Exception {
    String first = order("same", "SBSI", "BUY", 10).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String second = order("same", "SBSI", "BUY", 10).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(second).isEqualTo(first);
    assertThat(participant().getCash()).isEqualTo(INITIAL - 844_200); // 한 번만 빠졌다
  }

  @Test
  void I6_장이_닫힌_뒤_재전송해도_처음_결과를_돌려준다() throws Exception {
    order("retry", "SBSI", "BUY", 1).andExpect(status().isOk());
    clock.set(TestClockConfig.MARKET_OPEN_TIME.withHour(16));

    order("retry", "SBSI", "BUY", 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("FILLED"));
  }

  @Test
  void I6_같은_키로_다른_주문을_보내면_422() throws Exception {
    order("same", "SBSI", "BUY", 10).andExpect(status().isOk());

    order("same", "SBSI", "BUY", 11)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  }

  // ---------- 장 시간·대회 상태(I9) ----------

  @Test
  void I9_장_마감_시각에는_주문을_받지_않는다() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME.withHour(15).withMinute(30));

    order("late", "SBSI", "BUY", 1)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MARKET_CLOSED"));
  }

  @Test
  void I9_시작하지_않은_대회에는_주문할_수_없다() throws Exception {
    long scheduled = createContest(false);
    join(scheduled, userId).andExpect(status().isCreated()); // 참가는 시작 전에도 된다

    mvc.perform(post("/api/contests/{id}/orders", scheduled)
            .header("X-User-Id", userId).header("Idempotency-Key", "x")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("SBSI", "BUY", 1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("CONTEST_NOT_RUNNING"));
  }

  // ---------- 요청 오류 ----------

  @Test
  void 잘못된_요청은_400_없는_종목은_404_참가하지_않았으면_404() throws Exception {
    order("bad", "SBSI", "BUY", 0).andExpect(status().isBadRequest());
    order("bad", "SBSI", "HOLD", 1).andExpect(status().isBadRequest()); // 없는 enum 값
    order("none", "NOPE", "BUY", 1)
        .andExpect(status().isNotFound());

    mvc.perform(get("/api/contests/{id}/me", contestId).header("X-User-Id", createUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_PARTICIPANT"));
  }

  @Test
  void Spring_표준_오류는_500이_아니라_원래_상태_코드로_답한다() throws Exception {
    // 실제 앱 기동 확인 중 발견: 이 둘이 500 INTERNAL_ERROR 로 바뀌고 ERROR 로그를 남겼다
    mvc.perform(post("/api/contests/{id}/orders", contestId)
            .header("X-User-Id", userId).header("Idempotency-Key", "ct")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("ticker=SBSI"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    mvc.perform(get("/api/no-such-path"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  // ---------- 도우미 ----------

  private Participant participant() {
    return participantRepository.findByContestIdAndUserId(contestId, userId).orElseThrow();
  }

  private ResultActions order(String key, String ticker, String side, long qty) throws Exception {
    return mvc.perform(post("/api/contests/{id}/orders", contestId)
        .header("X-User-Id", userId)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(ticker, side, qty)));
  }

  private static String body(String ticker, String side, long qty) {
    return """
        {"ticker":"%s","side":"%s","quantity":%d}""".formatted(ticker, side, qty);
  }

  private ResultActions me() throws Exception {
    return mvc.perform(get("/api/contests/{id}/me", contestId).header("X-User-Id", userId))
        .andExpect(status().isOk());
  }

  private ResultActions join(long contest, long user) throws Exception {
    return mvc.perform(post("/api/contests/{id}/participants", contest).header("X-User-Id", user));
  }

  private long createUser() throws Exception {
    String nickname = "u-" + UUID.randomUUID().toString().substring(0, 8);
    String res = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"" + nickname + "\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(res, "$.data.id")).longValue();
  }

  private long createContest(boolean start) throws Exception {
    String res = mvc.perform(post("/api/admin/contests").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"12월 리그","startsOn":"2026-12-01","endsOn":"2026-12-31","seed":42}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.listedCount").value(24))
        .andReturn().getResponse().getContentAsString();
    long id = ((Number) JsonPath.read(res, "$.data.id")).longValue();
    if (start) {
      mvc.perform(post("/api/admin/contests/{id}/start", id)).andExpect(status().isOk());
    }
    return id;
  }
}
