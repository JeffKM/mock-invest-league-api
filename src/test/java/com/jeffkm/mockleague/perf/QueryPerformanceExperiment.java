package com.jeffkm.mockleague.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.market.PriceTickRepository;
import com.jeffkm.mockleague.ranking.RankingRepository;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4주차 조회 성능 실험. 기본 테스트에서는 건너뛴다(데이터를 수십만 행 넣느라 오래 걸린다).
 * 실행: LEAGUE_EXPERIMENT=true ./run_tests.sh --tests '*QueryPerformanceExperiment'
 *
 * <p>데이터(docs/01 5절 가정): 대회 10개 × 30일째, 종목 24개 × 78틱 → 가격 561,600행.
 * 대회마다 참가자 1,000명(보유 종목 약 5개), 참가자마다 주문 10건 + 한 명은 주문 5,000건(다른 사람들 주문보다 먼저).
 *
 * <p>쿼리마다 (1) 예전 문장과 지금 문장의 결과가 같은지, (2) EXPLAIN (ANALYZE, BUFFERS),
 * (3) 30번 실행한 서버 실행 시간 중앙값을 남긴다. 결과 줄은 [성능], 실행 계획은 build/perf/*.txt.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
@EnabledIfEnvironmentVariable(named = "LEAGUE_EXPERIMENT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryPerformanceExperiment {

  private static final LocalDate START = LocalDate.of(2027, 1, 1);
  private static final LocalDate LAST = START.plusDays(29);
  private static final int AT_TICK = 40;

  /** 3주차까지 쓰던 현재가 문장. 파라미터 (contestId, day, tick). */
  static final String OLD_LATEST_PRICES_SQL = """
      select distinct on (company_id) company_id, price
        from price_ticks
       where contest_id = ? and (trading_day, tick_no) <= (?, ?)
       order by company_id, trading_day desc, tick_no desc
      """;

  /** 3주차까지의 내 주문 목록 정렬. 파라미터 (participantId). */
  static final String OLD_RECENT_ORDERS_SQL = """
      select o.id, c.ticker
        from orders o join companies c on c.id = o.company_id
       where o.participant_id = ?
       order by o.id desc
       limit 50
      """;

  /** 지금 OrderRepository.findRecentWithCompany 와 같은 정렬. */
  static final String NEW_RECENT_ORDERS_SQL = """
      select o.id, c.ticker
        from orders o join companies c on c.id = o.company_id
       where o.participant_id = ?
       order by o.created_at desc, o.id desc
       limit 50
      """;

  @Autowired JdbcTemplate jdbc;
  @Autowired PostgreSQLContainer<?> container;

  long contestId;      // 측정 대상 대회(첫 번째)
  long heavyParticipant;
  final List<String> report = new ArrayList<>();

  @BeforeAll
  void seed() {
    long t0 = System.currentTimeMillis();
    jdbc.update("""
        insert into contests (name, status, starts_on, ends_on, seed)
        select 'perf-' || g, 'RUNNING', ?, ?, g from generate_series(1, 10) g
        """, Date.valueOf(START), Date.valueOf(LAST));
    jdbc.update("""
        insert into contest_listings (contest_id, company_id, base_price)
        select c.id, co.id, co.base_price from contests c cross join companies co where c.name like 'perf-%'
        """);
    // 가격 값 자체는 실험과 관계없지만, 날짜·틱마다 달라야 "예전·지금 결과가 같다"는 비교가 의미 있다
    jdbc.update("""
        insert into price_ticks (contest_id, company_id, trading_day, tick_no, price)
        select cl.contest_id, cl.company_id, d::date, t,
               cl.base_price + ((extract(doy from d)::int * 13 + t * 7 + cl.company_id::int * 3) % 500)
          from contest_listings cl
          join contests c on c.id = cl.contest_id and c.name like 'perf-%'
          cross join generate_series(?::date, ?::date, interval '1 day') d
          cross join generate_series(0, 77) t
        """, Date.valueOf(START), Date.valueOf(LAST));
    jdbc.update("insert into users (nickname) select 'perf-u-' || g from generate_series(1, 10000) g");
    jdbc.update("""
        insert into participants (contest_id, user_id, cash)
        select c.id, u.id, 5000000
          from (select id, row_number() over (order by id) rn from contests where name like 'perf-%') c
          join (select id, row_number() over (order by id) rn from users where nickname like 'perf-u-%') u
            on (u.rn - 1) / 1000 + 1 = c.rn
        """);
    jdbc.update("""
        insert into holdings (participant_id, company_id, quantity, total_cost)
        select p.id, co.id, 10, 10 * co.base_price
          from participants p
          join contests c on c.id = p.contest_id and c.name like 'perf-%'
          join companies co on (co.id + p.id) % 5 = 0
        """);
    contestId = jdbc.queryForObject("select min(id) from contests where name like 'perf-%'", Long.class);
    // 주문이 많은 참가자의 주문을 "먼저" 넣는다. 그 뒤에 다른 참가자들의 주문 10만 건이 쌓인다.
    // 반대로 넣으면 이 참가자의 주문이 id 맨 뒤에 몰려, 기본키를 뒤에서 읽기만 해도 50건이 바로 나온다(실제와 다른 최선의 경우)
    heavyParticipant = jdbc.queryForObject("select min(id) from participants where contest_id = ?", Long.class,
        contestId);
    jdbc.update("""
        insert into orders (participant_id, company_id, side, order_type, quantity, status, filled_price, fee,
                            idempotency_key, trading_day, placed_tick, filled_tick)
        select ?, (select min(id) from companies), 'BUY', 'MARKET', 1, 'FILLED', 1000, 5,
               'heavy-' || g, ?::date + (g % 30), g % 78, g % 78
          from generate_series(1, 5000) g
        """, heavyParticipant, Date.valueOf(START));
    jdbc.update("""
        insert into orders (participant_id, company_id, side, order_type, quantity, status, filled_price, fee,
                            idempotency_key, trading_day, placed_tick, filled_tick)
        select p.id, (select min(id) from companies), 'BUY', 'MARKET', 1, 'FILLED', 1000, 5,
               'perf-' || g, ?::date + (g % 30), g % 78, g % 78
          from participants p
          join contests c on c.id = p.contest_id and c.name like 'perf-%'
          cross join generate_series(1, 10) g
        """, Date.valueOf(START));
    jdbc.execute("analyze");
    line("데이터 준비 %d ms: 가격 %,d행, 참가자 %,d명, 보유 %,d행, 주문 %,d건",
        System.currentTimeMillis() - t0,
        count("price_ticks"), count("participants"), count("holdings"), count("orders"));
  }

  // ---------- A. 현재가 ----------

  @Test
  @Order(1)
  void A_현재가_distinct_on_vs_LATERAL() throws IOException {
    Object[] oldArgs = {contestId, Date.valueOf(LAST), AT_TICK};
    Object[] newArgs = {Date.valueOf(LAST), AT_TICK, contestId};

    assertThat(sortedRows(PriceTickRepository.LATEST_PRICES_SQL, newArgs))
        .isEqualTo(sortedRows(OLD_LATEST_PRICES_SQL, oldArgs))
        .hasSize(24);

    compare("A_현재가", OLD_LATEST_PRICES_SQL, oldArgs, PriceTickRepository.LATEST_PRICES_SQL, newArgs);
  }

  // ---------- B. 순위 스냅샷의 총자산 계산 ----------

  @Test
  @Order(2)
  void B_순위_총자산_계산() throws IOException {
    String oldSql = "with px as (" + OLD_LATEST_PRICES_SQL + ")\n" + RankingRepository.TOTALS_SQL;
    String newSql = "with px as (" + PriceTickRepository.LATEST_PRICES_SQL + ")\n" + RankingRepository.TOTALS_SQL;
    Object[] oldArgs = {contestId, Date.valueOf(LAST), AT_TICK, contestId};
    Object[] newArgs = {Date.valueOf(LAST), AT_TICK, contestId, contestId};

    assertThat(sortedRows(newSql, newArgs)).isEqualTo(sortedRows(oldSql, oldArgs)).hasSize(1000);
    compare("B_순위_총자산", oldSql, oldArgs, newSql, newArgs);
  }

  // ---------- C. 내 주문 목록(최근 50건) ----------

  @Test
  @Order(3)
  void C_내_주문_목록_정렬과_인덱스() throws IOException {
    long normal = jdbc.queryForObject("select max(id) from participants where contest_id = ?", Long.class, contestId);
    // 예전 인덱스로 되돌려 예전 쿼리를 잰다(이 컨테이너는 실험용이라 마음대로 바꿔도 된다)
    jdbc.execute("drop index idx_orders_participant_recent");
    jdbc.execute("create index idx_orders_participant_created on orders (participant_id, created_at desc)");
    jdbc.execute("analyze orders");
    List<String> heavyOld = sortedRows(OLD_RECENT_ORDERS_SQL, new Object[] {heavyParticipant});
    Measure heavyBefore = measure("C_주문목록_많은사람_예전", OLD_RECENT_ORDERS_SQL, new Object[] {heavyParticipant});
    Measure normalBefore = measure("C_주문목록_보통사람_예전", OLD_RECENT_ORDERS_SQL, new Object[] {normal});

    jdbc.execute("drop index idx_orders_participant_created");
    jdbc.execute("create index idx_orders_participant_recent on orders (participant_id, created_at desc, id desc)");
    jdbc.execute("analyze orders");
    // 같은 50건인지: 주문은 넣은 순서대로 created_at 이 늘고, 같은 시각이면 id 로 가른다
    assertThat(sortedRows(NEW_RECENT_ORDERS_SQL, new Object[] {heavyParticipant})).isEqualTo(heavyOld);
    Measure heavyAfter = measure("C_주문목록_많은사람_지금", NEW_RECENT_ORDERS_SQL, new Object[] {heavyParticipant});
    Measure normalAfter = measure("C_주문목록_보통사람_지금", NEW_RECENT_ORDERS_SQL, new Object[] {normal});

    summary("C_주문 5,010건·오래전에 몰린 참가자", heavyBefore, heavyAfter);
    summary("C_주문 10건 참가자", normalBefore, normalAfter);
  }

  // ---------- D. 가격 하루치 배치 INSERT ----------

  @Test
  @Order(4)
  void D_하루치_가격_배치_INSERT_reWriteBatchedInserts() {
    long target = jdbc.queryForObject("select max(id) from contests where name like 'perf-%'", Long.class);
    List<Long> companies = jdbc.queryForList(
        "select company_id from contest_listings where contest_id = ? order by company_id", Long.class, target);
    JdbcTemplate plain = template(false);
    JdbcTemplate rewrite = template(true);
    // 데우기: 연결 맺기·문장 준비 비용이 첫 측정에 섞이지 않게 한 번씩 먼저 넣는다
    plain.batchUpdate(PriceTickRepository.INSERT_SQL, dayRows(target, companies, LAST.plusDays(20)));
    rewrite.batchUpdate(PriceTickRepository.INSERT_SQL, dayRows(target, companies, LAST.plusDays(21)));

    List<Long> plainMs = new ArrayList<>();
    List<Long> rewriteMs = new ArrayList<>();
    // 30일 뒤의 빈 날짜에 번갈아 넣는다(한쪽만 데워진 상태로 재지 않게)
    for (int i = 0; i < 10; i++) {
      LocalDate day = LAST.plusDays(1 + i);
      JdbcTemplate t = (i % 2 == 0) ? plain : rewrite;
      long start = System.nanoTime();
      t.batchUpdate(PriceTickRepository.INSERT_SQL, dayRows(target, companies, day));
      long ms = (System.nanoTime() - start) / 1_000_000;
      (i % 2 == 0 ? plainMs : rewriteMs).add(ms);
      assertThat(jdbc.queryForObject(
          "select count(*) from price_ticks where contest_id = ? and trading_day = ?", Integer.class,
          target, Date.valueOf(day))).isEqualTo(24 * 78);
    }
    line("D_배치INSERT 1,872행: 기본 %s ms(중앙값 %d) → reWriteBatchedInserts %s ms(중앙값 %d)",
        plainMs, median(plainMs), rewriteMs, median(rewriteMs));
  }

  // ---------- 도구 ----------

  record Measure(String plan, double medianMs, long sharedHit, long sharedRead) {}

  private void compare(String name, String oldSql, Object[] oldArgs, String newSql, Object[] newArgs)
      throws IOException {
    Measure before = measure(name + "_예전", oldSql, oldArgs);
    Measure after = measure(name + "_지금", newSql, newArgs);
    summary(name, before, after);
  }

  /** EXPLAIN 을 한 번 남기고, 서버 실행 시간(Execution Time)을 30번 재서 중앙값을 낸다. */
  private Measure measure(String name, String sql, Object[] args) throws IOException {
    String plan = String.join("\n", jdbc.queryForList(
        "explain (analyze, buffers, costs off) " + sql, String.class, args));
    Path dir = Path.of("build/perf");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(name + ".txt"), plan);
    for (int i = 0; i < 5; i++) {
      jdbc.queryForList(sql, args); // 데우기
    }
    double[] times = new double[30];
    for (int i = 0; i < times.length; i++) {
      times[i] = executionMs(jdbc.queryForList("explain (analyze) " + sql, String.class, args));
    }
    Arrays.sort(times);
    long hit = sum(plan, "shared hit=");
    long read = sum(plan, "read=");
    return new Measure(plan, times[times.length / 2], hit, read);
  }

  private void summary(String name, Measure before, Measure after) {
    line("%s: %.3f ms → %.3f ms (%.0f배), 버퍼(8KB 블록) %,d → %,d",
        name, before.medianMs(), after.medianMs(), before.medianMs() / after.medianMs(),
        firstBuffers(before.plan()), firstBuffers(after.plan()));
  }

  private static double executionMs(List<String> plan) {
    return plan.stream().filter(l -> l.startsWith("Execution Time"))
        .map(l -> Double.parseDouble(l.replaceAll("[^0-9.]", "")))
        .findFirst().orElseThrow();
  }

  /** 실행 계획 맨 위 노드의 Buffers 합(hit + read). 맨 위 노드는 아래 노드를 모두 포함한다. */
  private static long firstBuffers(String plan) {
    for (String l : plan.split("\n")) {
      if (l.trim().startsWith("Buffers:")) {
        return sum(l, "hit=") + sum(l, "read=");
      }
    }
    return 0;
  }

  private static long sum(String text, String key) {
    int i = text.indexOf(key);
    if (i < 0) {
      return 0;
    }
    String rest = text.substring(i + key.length());
    return Long.parseLong(rest.replaceAll("^(\\d+).*", "$1").split("\\D")[0]);
  }

  private List<String> sortedRows(String sql, Object[] args) {
    return jdbc.queryForList(sql, args).stream().map(Object::toString).sorted().toList();
  }

  private List<Object[]> dayRows(long contest, List<Long> companies, LocalDate day) {
    List<Object[]> rows = new ArrayList<>();
    for (Long c : companies) {
      for (int t = 0; t < 78; t++) {
        rows.add(new Object[] {contest, c, Date.valueOf(day), t, 10_000L + t});
      }
    }
    return rows;
  }

  private JdbcTemplate template(boolean rewrite) {
    String url = container.getJdbcUrl() + (container.getJdbcUrl().contains("?") ? "&" : "?")
        + "reWriteBatchedInserts=" + rewrite;
    // 연결 하나를 계속 쓴다. 매번 새로 맺으면 연결 비용(인증 등)이 두 방식 모두에 섞인다
    SingleConnectionDataSource ds = new SingleConnectionDataSource(url, container.getUsername(),
        container.getPassword(), true);
    return new JdbcTemplate(ds);
  }

  private static long median(List<Long> values) {
    List<Long> s = values.stream().sorted().toList();
    return s.get(s.size() / 2);
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  private void line(String format, Object... args) {
    String l = "[성능] " + format.formatted(args);
    report.add(l);
    System.out.println(l);
  }

}
