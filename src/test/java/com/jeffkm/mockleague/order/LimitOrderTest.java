package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.account.AccountService;
import com.jeffkm.mockleague.account.CashLedgerRepository;
import com.jeffkm.mockleague.account.Holding;
import com.jeffkm.mockleague.account.HoldingId;
import com.jeffkm.mockleague.account.HoldingRepository;
import com.jeffkm.mockleague.account.Participant;
import com.jeffkm.mockleague.account.ParticipantRepository;
import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestLifecycleService;
import com.jeffkm.mockleague.contest.ContestService;
import com.jeffkm.mockleague.contest.dto.CreateContestRequest;
import com.jeffkm.mockleague.market.PriceTickRepository;
import com.jeffkm.mockleague.order.dto.OrderResponse;
import com.jeffkm.mockleague.order.dto.PlaceOrderRequest;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import com.jeffkm.mockleague.user.UserService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 지정가 주문(F8·F9·I10) 통합 테스트. 가격 경로는 시드로 정해지므로, 테스트는 DB 의 틱 가격을 읽어
 * "언제 닿는지"를 직접 계산한 뒤 정산 결과와 비교한다(특정 숫자를 외워 두지 않는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class LimitOrderTest {

  private static final LocalDate DAY = LocalDate.of(2026, 12, 1);
  private static final long INITIAL = Contest.DEFAULT_INITIAL_CASH;
  private static final int LAST = 77;

  @Autowired MockMvc mvc;
  @Autowired OrderFacade orderFacade;
  @Autowired OrderService orderService;
  @Autowired LimitOrderSettlementService settlementService;
  @Autowired ContestLifecycleService lifecycleService;
  @Autowired ContestService contestService;
  @Autowired UserService userService;
  @Autowired AccountService accountService;
  @Autowired ParticipantRepository participantRepository;
  @Autowired HoldingRepository holdingRepository;
  @Autowired CompanyRepository companyRepository;
  @Autowired OrderRepository orderRepository;
  @Autowired CashLedgerRepository ledgerRepository;
  @Autowired PriceTickRepository priceTickRepository;
  @Autowired MutableClock clock;

  long contestId;
  long userId;
  long participantId;

  @BeforeEach
  void setUp() {
    clock.set(TestClockConfig.MARKET_OPEN_TIME); // 12/1 09:00, 0번 틱
    contestId = contestService.create(new CreateContestRequest("지정가", DAY, DAY.plusDays(30), 42L)).id();
    contestService.start(contestId);
    userId = userService.create("l-" + UUID.randomUUID().toString().substring(0, 8)).id();
    participantId = accountService.join(contestId, userId).participantId();
  }

  // ---------- 접수 ----------

  @Test
  void 매수_지정가를_내면_금액과_수수료만큼_현금이_묶이고_PENDING_이다() {
    OrderResponse res = limit("k", "SBSI", OrderSide.BUY, 10, 80_000);

    assertThat(res.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(res.amount()).isNull();
    Participant p = participant();
    assertThat(p.getReservedCash()).isEqualTo(800_000 + 4_000);
    assertThat(p.getCash()).isEqualTo(INITIAL - 804_000);
    assertI5();
  }

  @Test
  void 묶인_주식은_시장가로_팔_수_없다() {
    market("buy", OrderSide.BUY, 10);
    limit("sell-limit", "SBSI", OrderSide.SELL, 10, 999_999);

    assertThatThrownBy(() -> market("sell", OrderSide.SELL, 1))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.INSUFFICIENT_QUANTITY);
  }

  // ---------- 체결·만료 ----------

  @Test
  void 매수_지정가는_처음_닿은_틱에_지정가로_체결된다() {
    long[] path = path("SBSI");
    long limit = Arrays.stream(path).min().orElseThrow();
    int expectedTick = firstTick(path, p -> p <= limit);
    limit("k", "SBSI", OrderSide.BUY, 10, limit);

    settleAt(15, 30);

    Order o = onlyOrder();
    assertThat(o.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(o.getFilledTick()).isEqualTo(expectedTick);
    assertThat(o.getFilledPrice()).isEqualTo(limit);
    // 체결 시각은 배치가 돈 시각이 아니라 가격이 닿은 틱의 시각
    assertThat(o.getFilledAt()).isEqualTo(DAY.atTime(9, 0).plusMinutes(5L * expectedTick)
        .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant());
    long amount = limit * 10;
    Participant p = participant();
    assertThat(p.getReservedCash()).isZero();
    assertThat(p.getCash()).isEqualTo(INITIAL - amount - FeePolicy.fee(amount, 50));
    assertThat(holding("SBSI").getQuantity()).isEqualTo(10);
    assertI5();
  }

  @Test
  void 장_중_정산은_지금_틱까지만_보고_미래_가격으로_체결하지_않는다() {
    // 최저가가 늦게(10번 틱 이후) 나오는 종목을 골라, 그 직전 틱에는 아직 대기여야 함을 본다
    String ticker = tickerWithLateLow(10);
    long[] path = path(ticker);
    long limit = Arrays.stream(path).min().orElseThrow();
    int touch = firstTick(path, p -> p <= limit);
    limit("k", ticker, OrderSide.BUY, 1, limit);

    clock.set(DAY.atTime(9, 0).plusMinutes(5L * (touch - 1)));
    settlementService.settle(contestId, java.time.LocalDateTime.now(clock));
    assertThat(onlyOrder().getStatus()).as("닿기 한 틱 전").isEqualTo(OrderStatus.PENDING);

    clock.set(DAY.atTime(9, 0).plusMinutes(5L * touch));
    settlementService.settle(contestId, java.time.LocalDateTime.now(clock));
    assertThat(onlyOrder().getStatus()).as("닿은 틱").isEqualTo(OrderStatus.FILLED);
    assertThat(onlyOrder().getFilledTick()).isEqualTo(touch);
  }

  @Test
  void 끝내_닿지_않은_매수는_마감_후_만료되고_묶인_현금이_전부_돌아온다() {
    long below = Arrays.stream(path("SBSI")).min().orElseThrow() - 1;
    limit("k", "SBSI", OrderSide.BUY, 10, below);

    settleAt(10, 0);
    assertThat(onlyOrder().getStatus()).as("장 중에는 아직 대기").isEqualTo(OrderStatus.PENDING);

    settleAt(15, 30);
    assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(participant().getCash()).isEqualTo(INITIAL);
    assertThat(participant().getReservedCash()).isZero();
    assertI5();
  }

  @Test
  void 매도_지정가는_닿으면_지정가로_팔리고_못_닿으면_주식이_풀린다() {
    long[] path = path("SBSI");
    long high = Arrays.stream(path).max().orElseThrow();
    market("buy", OrderSide.BUY, 20); // 0번 틱 84,000원
    long cashAfterBuy = participant().getCash();
    limit("sell-hit", "SBSI", OrderSide.SELL, 10, high);
    limit("sell-miss", "SBSI", OrderSide.SELL, 10, high + 1);
    assertThat(holding("SBSI").getReservedQuantity()).isEqualTo(20);

    settleAt(15, 30);

    long amount = high * 10;
    assertThat(statusOf("sell-hit")).isEqualTo(OrderStatus.FILLED);
    assertThat(statusOf("sell-miss")).isEqualTo(OrderStatus.EXPIRED);
    assertThat(participant().getCash()).isEqualTo(cashAfterBuy + amount - FeePolicy.fee(amount, 50));
    assertThat(holding("SBSI").getQuantity()).isEqualTo(10);
    assertThat(holding("SBSI").getReservedQuantity()).isZero();
    assertI5();
  }

  @Test
  void 처리되지_못한_주문은_다음_날_배치가_그날_가격으로_마저_정산한다() {
    long below = Arrays.stream(path("SBSI")).min().orElseThrow() - 1;
    limit("k", "SBSI", OrderSide.BUY, 1, below);

    lifecycleService.runDaily(DAY.plusDays(1)); // 12/1 장 중 배치가 한 번도 안 돌았다고 가정

    assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(participant().getCash()).isEqualTo(INITIAL);
  }

  // ---------- 취소 ----------

  @Test
  void 대기_주문을_취소하면_묶인_현금이_돌아오고_다시_취소하면_409() {
    OrderResponse placed = limit("k", "SBSI", OrderSide.BUY, 10, 1_000);

    OrderResponse canceled = orderService.cancel(contestId, userId, placed.orderId());

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
    assertThat(participant().getCash()).isEqualTo(INITIAL);
    assertThatThrownBy(() -> orderService.cancel(contestId, userId, placed.orderId()))
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.ORDER_NOT_CANCELABLE);
    settleAt(15, 30);
    assertThat(onlyOrder().getStatus()).as("취소된 주문은 배치가 건드리지 않는다").isEqualTo(OrderStatus.CANCELED);
    assertI5();
  }

  @Test
  void 체결된_시장가_주문은_취소할_수_없다() {
    OrderResponse filled = market("m", OrderSide.BUY, 1);
    assertThatThrownBy(() -> orderService.cancel(contestId, userId, filled.orderId()))
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.ORDER_NOT_CANCELABLE);
  }

  // ---------- I10: 정확히 한 번 ----------

  @Test
  void I10_정산을_두_번_돌려도_결과가_같다() {
    long[] path = path("SBSI");
    long low = Arrays.stream(path).min().orElseThrow();
    limit("hit", "SBSI", OrderSide.BUY, 3, low);
    limit("miss", "SBSI", OrderSide.BUY, 3, low - 1);

    settleAt(15, 30);
    long cash = participant().getCash();
    long ledgerRows = ledgerRepository.count();

    var second = settlementService.settle(contestId, java.time.LocalDateTime.now(clock));

    assertThat(second).as("두 번째에는 대기 주문이 없다").isEmpty();
    assertThat(participant().getCash()).isEqualTo(cash);
    assertThat(ledgerRepository.count()).isEqualTo(ledgerRows);
  }

  @Test
  void I10_정산_4개와_취소가_동시에_달려들어도_주문마다_정확히_한_번_끝난다() throws Exception {
    long low = Arrays.stream(path("SBSI")).min().orElseThrow();
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      long price = (i % 2 == 0) ? low : low - 1; // 짝수는 체결될 주문, 홀수는 만료될 주문
      ids.add(limit("o-" + i, "SBSI", OrderSide.BUY, 1, price).orderId());
    }
    clock.set(DAY.atTime(15, 30));

    ExecutorService pool = Executors.newFixedThreadPool(5);
    CountDownLatch start = new CountDownLatch(1);
    List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < 4; t++) {
      futures.add(pool.submit(() -> {
        start.await();
        return settlementService.settle(contestId, DAY.atTime(15, 30));
      }));
    }
    futures.add(pool.submit(() -> {
      start.await();
      for (Long id : ids) {
        try {
          orderService.cancel(contestId, userId, id);
        } catch (BusinessException e) {
          // 배치가 먼저 끝낸 주문은 409. 정상
        }
      }
      return null;
    }));
    start.countDown();
    for (var f : futures) {
      f.get(60, TimeUnit.SECONDS);
    }
    pool.shutdown();

    List<Order> orders = orderRepository.findAllById(ids);
    assertThat(orders).allMatch(o -> o.getStatus().isFinal());
    long filled = orders.stream().filter(o -> o.getStatus() == OrderStatus.FILLED).count();
    long ledgerTrades = ledgerRepository.findAll().stream()
        .filter(l -> l.getParticipant().getId().equals(participantId) && l.getOrder() != null
            && l.getEntryType() == com.jeffkm.mockleague.account.LedgerEntryType.BUY)
        .count();
    assertThat(ledgerTrades).as("체결 1건 = 원장 BUY 1줄(이중 체결 없음)").isEqualTo(filled);
    assertThat(participant().getReservedCash()).isZero();
    assertThat(participant().getCash()).isEqualTo(INITIAL - filled * (low + FeePolicy.fee(low, 50)));
    assertI5();
  }

  // ---------- HTTP 입구 ----------

  @Test
  void 지정가에_가격이_없거나_시장가에_가격이_있으면_400() throws Exception {
    postOrder("a", """
        {"ticker":"SBSI","side":"BUY","quantity":1,"type":"LIMIT"}""")
        .andExpect(status().isBadRequest());
    postOrder("b", """
        {"ticker":"SBSI","side":"BUY","quantity":1,"type":"MARKET","limitPrice":80000}""")
        .andExpect(status().isBadRequest());
  }

  @Test
  void 같은_키로_지정가만_바꿔_보내면_422() throws Exception {
    limit("same", "SBSI", OrderSide.BUY, 1, 80_000);
    postOrder("same", """
        {"ticker":"SBSI","side":"BUY","quantity":1,"type":"LIMIT","limitPrice":79000}""")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void HTTP로_지정가를_내고_목록에서_보고_취소한다() throws Exception {
    String body = postOrder("h", """
            {"ticker":"SBSI","side":"BUY","quantity":2,"type":"LIMIT","limitPrice":70000}""")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.limitPrice").value(70_000))
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.orderId")).longValue();

    mvc.perform(get("/api/contests/{c}/orders", contestId).header("X-User-Id", userId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].orderId").value(orderId));
    mvc.perform(delete("/api/contests/{c}/orders/{o}", contestId, orderId).header("X-User-Id", userId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELED"));
    mvc.perform(delete("/api/contests/{c}/orders/{o}", contestId, orderId).header("X-User-Id", userId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ORDER_NOT_CANCELABLE"));
  }

  // ---------- 도구 ----------

  OrderResponse limit(String key, String ticker, OrderSide side, long qty, long price) {
    return orderFacade.placeOrder(contestId, userId, key, PlaceOrderRequest.limit(ticker, side, qty, price));
  }

  OrderResponse market(String key, OrderSide side, long qty) {
    return orderFacade.placeOrder(contestId, userId, key, new PlaceOrderRequest("SBSI", side, qty));
  }

  org.springframework.test.web.servlet.ResultActions postOrder(String key, String json) throws Exception {
    return mvc.perform(post("/api/contests/{c}/orders", contestId)
        .header("X-User-Id", userId).header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content(json));
  }

  @Test
  void 관리자_정산_API는_지금_틱까지_닿은_주문을_체결하고_건수를_돌려준다() throws Exception {
    String ticker = tickerWithLateLow(10);
    long[] p = path(ticker);
    long limitPrice = p[0] - 1;                   // 0번 틱 가격보다 1원 낮게: 지금은 안 닿는다
    int touch = firstTick(p, x -> x <= limitPrice);
    limit("api-settle", ticker, OrderSide.BUY, 1, limitPrice);

    clock.set(DAY.atTime(9, 0));                  // 아직 0번 틱 → 대기
    mvc.perform(post("/api/admin/contests/{id}/limit-settlements", contestId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.filled").value(0))
        .andExpect(jsonPath("$.data.pending").value(1));

    clock.set(DAY.atTime(9, 0).plusMinutes(5L * touch));   // 닿은 틱이 공개된 뒤
    mvc.perform(post("/api/admin/contests/{id}/limit-settlements", contestId))
        .andExpect(jsonPath("$.data.filled").value(1))
        .andExpect(jsonPath("$.data.elapsedMs").isNumber());
    assertThat(statusOf("api-settle")).isEqualTo(OrderStatus.FILLED);

    mvc.perform(post("/api/admin/contests/{id}/limit-settlements", contestId))   // 더 할 것이 없다
        .andExpect(jsonPath("$.data.filled").value(0))
        .andExpect(jsonPath("$.data.pending").value(0));
    assertI5();
  }

  void settleAt(int hour, int minute) {
    clock.set(DAY.atTime(hour, minute));
    settlementService.settle(contestId, java.time.LocalDateTime.now(clock));
  }

  long[] path(String ticker) {
    long companyId = companyRepository.findByTicker(ticker).orElseThrow().getId();
    long[] p = new long[LAST + 1];
    for (int t = 0; t <= LAST; t++) {
      p[t] = priceTickRepository.priceAt(contestId, companyId, DAY, t).orElseThrow();
    }
    return p;
  }

  /** 하루 최저가가 처음 나오는 틱이 minTick 이후인 종목. 시드가 고정이라 항상 같은 종목이 나온다. */
  String tickerWithLateLow(int minTick) {
    for (var c : companyRepository.findAll()) {
      long[] p = path(c.getTicker());
      long low = Arrays.stream(p).min().orElseThrow();
      if (firstTick(p, x -> x <= low) >= minTick) {
        return c.getTicker();
      }
    }
    throw new IllegalStateException("조건에 맞는 종목이 없다(시드를 바꿔 볼 것)");
  }

  static int firstTick(long[] path, java.util.function.LongPredicate touched) {
    for (int t = 0; t < path.length; t++) {
      if (touched.test(path[t])) {
        return t;
      }
    }
    return -1;
  }

  Participant participant() {
    return participantRepository.findById(participantId).orElseThrow();
  }

  Holding holding(String ticker) {
    long companyId = companyRepository.findByTicker(ticker).orElseThrow().getId();
    return holdingRepository.findById(new HoldingId(participantId, companyId)).orElseThrow();
  }

  Order onlyOrder() {
    List<Order> all = orderRepository.findRecentWithCompany(participantId,
        org.springframework.data.domain.PageRequest.of(0, 10));
    assertThat(all).hasSize(1);
    return all.get(0);
  }

  OrderStatus statusOf(String key) {
    return orderRepository.findByParticipantIdAndIdempotencyKey(participantId, key).orElseThrow().getStatus();
  }

  /** I5: 현금 + 묶인 현금 = 원장 합계. 묶기·풀기는 원장에 없으므로 좌변 합으로 맞아야 한다. */
  void assertI5() {
    Participant p = participant();
    assertThat(ledgerRepository.sumAmountByParticipantId(participantId))
        .as("I5 원장 합계 = 현금 + 묶인 현금").isEqualTo(p.getCash() + p.getReservedCash());
  }
}
