package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.account.AccountService;
import com.jeffkm.mockleague.account.CashLedgerRepository;
import com.jeffkm.mockleague.account.HoldingId;
import com.jeffkm.mockleague.account.HoldingRepository;
import com.jeffkm.mockleague.account.ParticipantRepository;
import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestService;
import com.jeffkm.mockleague.contest.dto.CreateContestRequest;
import com.jeffkm.mockleague.order.dto.PlaceOrderRequest;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.TestClockConfig;
import com.jeffkm.mockleague.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 같은 계좌로 주문을 동시에 보내는 시나리오 모음. 락 방식마다 하위 클래스가 설정만 바꿔 같은 시나리오를 돌린다.
 * 컨트롤러를 거치지 않고 OrderFacade 를 직접 부른다. 보려는 것은 HTTP 가 아니라 트랜잭션끼리의 경합이다.
 *
 * <p>모든 시나리오가 확인하는 것(성공 건수가 몇이든 반드시 맞아야 하는 것):
 * 현금 = 처음 − 체결분 합계, 보유 수량 = 체결분 합계, 원장 합계 = 현금(I5).
 */
abstract class ConcurrentOrderScenarios {

  static final int THREADS = 32;
  static final long INITIAL = Contest.DEFAULT_INITIAL_CASH; // 10,000,000
  static final long PRICE = 84_000;   // SBSI 기준가
  static final long FEE = 420;        // 84,000 × 0.5%

  @Autowired OrderFacade orderFacade;
  @Autowired UserService userService;
  @Autowired ContestService contestService;
  @Autowired AccountService accountService;
  @Autowired ParticipantRepository participantRepository;
  @Autowired HoldingRepository holdingRepository;
  @Autowired CompanyRepository companyRepository;
  @Autowired OrderRepository orderRepository;
  @Autowired CashLedgerRepository ledgerRepository;
  @Autowired MeterRegistry meterRegistry;
  @Autowired MutableClock clock;

  long contestId;
  long userId;
  long participantId;

  /** 이 방식이면 재시도 없이도(또는 재시도 안에서) 모든 요청이 체결돼야 하나. */
  abstract boolean expectsAllToSucceed();

  @BeforeEach
  void setUp() {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    userId = userService.create("c-" + UUID.randomUUID().toString().substring(0, 8)).id();
    contestId = contestService.create(new CreateContestRequest("동시성", LocalDate.of(2026, 12, 1),
        LocalDate.of(2026, 12, 31), 42L)).id();
    contestService.start(contestId);
    participantId = accountService.join(contestId, userId).participantId();
  }

  @Test
  void 매수_100건을_동시에_보내도_현금과_수량이_맞는다() throws Exception {
    // 보유 행을 미리 만든다. 이 시나리오는 "잔고·수량 덮어쓰기"만 본다(첫 INSERT 경합은 아래 시나리오)
    order("warm-up", OrderSide.BUY);
    long cashBefore = cash();

    Result r = runConcurrently("매수 100건", 100, i -> "buy-" + i, OrderSide.BUY);

    assertMoney(r, cashBefore - r.success * (PRICE + FEE), 1 + r.success);
    if (expectsAllToSucceed()) {
      assertThat(r.success).isEqualTo(100);
    }
  }

  @Test
  void 가진_50주보다_많은_매도_100건이_동시에_와도_50건만_체결된다() throws Exception {
    for (int i = 0; i < 50; i++) {
      order("setup-" + i, OrderSide.BUY);
    }
    long cashBefore = cash();

    Result r = runConcurrently("매도 100건(보유 50주)", 100, i -> "sell-" + i, OrderSide.SELL);

    assertThat(r.success).as("팔 수 있는 만큼만 체결(I2)").isLessThanOrEqualTo(50);
    assertMoney(r, cashBefore + r.success * (PRICE - FEE), 50 - r.success);
    if (expectsAllToSucceed()) {
      assertThat(r.success).isEqualTo(50);
      assertThat(r.failures).containsEntry("INSUFFICIENT_QUANTITY", 50);
    }
  }

  @Test
  void 처음_사는_종목을_동시에_사도_보유_행은_하나다() throws Exception {
    Result r = runConcurrently("첫 매수 20건", 20, i -> "first-" + i, OrderSide.BUY);

    assertMoney(r, INITIAL - r.success * (PRICE + FEE), r.success);
    if (expectsAllToSucceed()) {
      assertThat(r.success).isEqualTo(20);
    }
  }

  @Test
  void 같은_키로_동시에_10번_보내면_주문은_1건이고_모두_같은_주문을_받는다() throws Exception {
    Map<Long, Integer> orderIds = new ConcurrentHashMap<>();
    Result r = runConcurrently("같은 키 10건", 10, i -> "same-key", OrderSide.BUY,
        id -> orderIds.merge(id, 1, Integer::sum));

    assertThat(orderRepository.countByParticipantId(participantId)).as("주문은 1건").isEqualTo(1);
    assertMoney(r, INITIAL - (PRICE + FEE), 1);
    assertThat(orderIds).as("성공한 응답은 모두 같은 주문 id").hasSize(1);
    if (expectsAllToSucceed()) {
      assertThat(r.success).as("10건 모두 정상 응답(재요청은 첫 결과)").isEqualTo(10);
    }
  }

  // ---------- 검증·실행 도구 ----------

  void assertMoney(Result r, long expectedCash, long expectedQty) {
    long cash = cash();
    long qty = quantity();
    System.out.printf("%n[동시성] %s | %s | 요청 %d, 성공 %d, 실패 %s, 재시도 %s, %dms%n"
            + "[동시성]   현금 기대 %,d / 실제 %,d (차이 %,d) | 수량 기대 %d / 실제 %d%n",
        getClass().getSimpleName(), r.title, r.requested, r.success, r.failures, r.retries, r.elapsedMs,
        expectedCash, cash, cash - expectedCash, expectedQty, qty);
    assertThat(cash).as("현금 = 처음 − 체결분 합계(갱신 손실 없음)").isEqualTo(expectedCash);
    assertThat(qty).as("보유 수량 = 체결분 합계").isEqualTo(expectedQty);
    assertThat(ledgerRepository.sumAmountByParticipantId(participantId)).as("I5 원장 합계 = 현금").isEqualTo(cash);
  }

  void order(String key, OrderSide side) {
    orderFacade.placeOrder(contestId, userId, key, new PlaceOrderRequest("SBSI", side, 1));
  }

  interface KeyFn { String key(int i); }
  interface OnSuccess { void accept(Long orderId); }

  Result runConcurrently(String title, int n, KeyFn keys, OrderSide side) throws InterruptedException {
    return runConcurrently(title, n, keys, side, id -> {});
  }

  /**
   * n 개의 1주 주문을 THREADS 개 스레드에서 "동시에 출발"시킨다.
   * ready: 스레드가 모두 출발선에 설 때까지 / start: 한꺼번에 출발 / done: 전부 끝날 때까지.
   * 출발선이 없으면 먼저 만든 스레드가 먼저 끝나 버려 경합이 잘 생기지 않는다.
   */
  Result runConcurrently(String title, int n, KeyFn keys, OrderSide side, OnSuccess onSuccess)
      throws InterruptedException {
    double retriesBefore = retryCount();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch ready = new CountDownLatch(Math.min(n, THREADS));
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(n);
    AtomicInteger success = new AtomicInteger();
    Map<String, Integer> failures = new ConcurrentHashMap<>();
    try {
      for (int i = 0; i < n; i++) {
        String key = keys.key(i);
        boolean first = i < THREADS;
        pool.submit(() -> {
          if (first) {
            ready.countDown();
          }
          try {
            start.await();
            var res = orderFacade.placeOrder(contestId, userId, key,
                new PlaceOrderRequest("SBSI", side, 1));
            success.incrementAndGet();
            onSuccess.accept(res.orderId());
          } catch (BusinessException e) {
            failures.merge(e.errorCode().name(), 1, Integer::sum);
          } catch (Exception e) {
            failures.merge(e.getClass().getSimpleName(), 1, Integer::sum);
          } finally {
            done.countDown();
          }
        });
      }
      ready.await();
      long t0 = System.nanoTime();
      start.countDown();
      assertThat(done.await(120, TimeUnit.SECONDS)).as("120초 안에 끝나야 한다").isTrue();
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
      return new Result(title, n, success.get(), new TreeMap<>(failures), (long) (retryCount() - retriesBefore), ms);
    } finally {
      pool.shutdownNow();
    }
  }

  double retryCount() {
    return meterRegistry.find("orders.retry").counters().stream().mapToDouble(c -> c.count()).sum();
  }

  long cash() {
    return participantRepository.findById(participantId).orElseThrow().getCash();
  }

  long quantity() {
    long companyId = companyRepository.findByTicker("SBSI").orElseThrow().getId();
    return holdingRepository.findById(new HoldingId(participantId, companyId))
        .map(h -> h.getQuantity()).orElse(0L);
  }

  record Result(String title, int requested, int success, Map<String, Integer> failures, long retries,
      long elapsedMs) {}
}
