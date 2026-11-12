package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.contest.TickPosition;
import com.jeffkm.mockleague.order.OrderSide;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 틱 가격 저장소. JPA 엔티티 대신 JdbcTemplate 을 쓴다.
 * 하루에 종목 24개 × 78틱 = 1,872행을 한 번에 넣는데, 엔티티로 넣으면 객체 1,872개를 영속성 컨텍스트에 올리고
 * 더티 체킹 대상으로 들고 있게 된다. 가격 행은 한 번 쓰면 바뀌지 않으므로 그런 관리가 필요 없다.
 */
@Repository
public class PriceTickRepository {

  private final JdbcTemplate jdbc;

  public PriceTickRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 이 대회에서 가격을 만든 마지막 날. 없으면 비어 있음. */
  public Optional<LocalDate> lastGeneratedDay(Long contestId) {
    LocalDate day = jdbc.queryForObject(
        "select max(trading_day) from price_ticks where contest_id = ?", LocalDate.class, contestId);
    return Optional.ofNullable(day);
  }

  /** 그날 각 종목의 종가(마지막 틱 가격). companyId → 가격. */
  public Map<Long, Long> closesOf(Long contestId, LocalDate day) {
    Map<Long, Long> closes = new HashMap<>();
    jdbc.query("""
        select distinct on (company_id) company_id, price
          from price_ticks
         where contest_id = ? and trading_day = ?
         order by company_id, tick_no desc
        """, rs -> {
          closes.put(rs.getLong("company_id"), rs.getLong("price"));
        }, contestId, Date.valueOf(day));
    return closes;
  }

  /**
   * 하루치 가격을 한 번에 넣는다(JDBC 배치: 문장 하나를 값만 바꿔 묶어 보낸다).
   * ON CONFLICT DO NOTHING: 배치를 두 번 돌리거나 서버 두 대가 동시에 돌려도 오류 없이 한 벌만 남는다.
   * 경로가 결정적이므로(같은 시드 → 같은 값) 누가 먼저 넣든 내용이 같다.
   *
   * <p>드라이버 설정 reWriteBatchedInserts=true(application.yml)가 1,872개 문장을 여러 행 INSERT 몇 개로 합친다.
   * 그 대신 드라이버가 행마다 "몇 행 들어갔는지"를 알려 주지 않는다(SUCCESS_NO_INFO). 그래서 개수를 돌려주지 않는다.
   */
  public void insertDay(Long contestId, LocalDate day, Map<Long, long[]> pathsByCompany) {
    List<Object[]> rows = new ArrayList<>();
    Date sqlDay = Date.valueOf(day);
    pathsByCompany.forEach((companyId, prices) -> {
      for (int tick = 0; tick < prices.length; tick++) {
        rows.add(new Object[] {contestId, companyId, sqlDay, tick, prices[tick]});
      }
    });
    jdbc.batchUpdate(INSERT_SQL, rows);
  }

  /** 가격 한 행 INSERT. 성능 실험이 같은 문장으로 비교하도록 공개한다. */
  public static final String INSERT_SQL = """
      insert into price_ticks (contest_id, company_id, trading_day, tick_no, price)
      values (?, ?, ?, ?, ?)
      on conflict do nothing
      """;

  /** 정확히 그 틱의 가격(주문 체결용). */
  public Optional<Long> priceAt(Long contestId, Long companyId, LocalDate day, int tick) {
    List<Long> found = jdbc.queryForList("""
        select price from price_ticks
         where contest_id = ? and company_id = ? and trading_day = ? and tick_no = ?
        """, Long.class, contestId, companyId, Date.valueOf(day), tick);
    return found.stream().findFirst();
  }

  /**
   * 지정가에 처음 닿은 틱(F8). 매수는 가격 ≤ 지정가, 매도는 가격 ≥ 지정가인 첫 틱이다.
   * 범위는 [fromTick, toTick]. toTick 을 "지금 틱"으로 넘기면 미래 가격으로 체결하는 일이 없다.
   */
  public Optional<Integer> firstTouchTick(Long contestId, Long companyId, LocalDate day, int fromTick,
      int toTick, OrderSide side, long limitPrice) {
    String cond = (side == OrderSide.BUY) ? "price <= ?" : "price >= ?";
    List<Integer> found = jdbc.queryForList("""
        select tick_no from price_ticks
         where contest_id = ? and company_id = ? and trading_day = ?
           and tick_no between ? and ? and %s
         order by tick_no
         limit 1
        """.formatted(cond), Integer.class, contestId, companyId, Date.valueOf(day), fromTick, toTick, limitPrice);
    return found.stream().findFirst();
  }

  /**
   * 지점 at 까지(포함) 각 종목의 가장 최근 가격(companyId, price). 파라미터 순서: (contestId, day, tick).
   * 순위 스냅샷 쿼리도 이 문장을 CTE 로 재사용한다(가격 기준이 하나여야 순위와 내 계좌 평가액이 같다).
   *
   * <p>4주차에 바꿨다. 예전 문장은 "대회의 at 이전 행 전부"를 읽어 정렬한 뒤 종목마다 첫 행을 골랐다
   * ({@code distinct on}). 기본키가 (contest_id, company_id, trading_day, tick_no) 라서,
   * contest_id 다음 열이 company_id 인데 조건은 trading_day 에 걸려 있어 인덱스로 범위를 좁히지 못했다.
   * 대회가 30일째면 종목 24개 × 78틱 × 30일 = 56,160행을 읽는다.
   *
   * <p>지금 문장은 상장 종목마다(LATERAL) "(contest_id, company_id) 가 같고 at 이하인 행 중 가장 뒤의 1행"을 찾는다.
   * 기본키 순서와 정확히 맞으므로 인덱스를 뒤에서부터 한 칸만 읽고 멈춘다. 종목 24개 → 인덱스 탐색 24번.
   * 결과는 ROADMAP 18단계와 perf/QueryPerformanceExperiment 참고.
   */
  public static final String LATEST_PRICES_SQL = """
      select cl.company_id, p.price
        from contest_listings cl
        cross join lateral (
          select pt.price
            from price_ticks pt
           where pt.contest_id = cl.contest_id and pt.company_id = cl.company_id
             and (pt.trading_day, pt.tick_no) <= (?, ?)
           order by pt.trading_day desc, pt.tick_no desc
           limit 1
        ) p
       where cl.contest_id = ?
      """;

  /** 지점 at 까지(포함) 각 종목의 가장 최근 가격. at 보다 뒤(미래)의 행은 조건에서 빠진다(I11). */
  public Map<Long, Long> latestPricesUpTo(Long contestId, TickPosition at) {
    Map<Long, Long> prices = new HashMap<>();
    jdbc.query(LATEST_PRICES_SQL, rs -> {
      prices.put(rs.getLong("company_id"), rs.getLong("price"));
    }, Date.valueOf(at.day()), at.tick(), contestId);
    return prices;
  }
}
