package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.contest.TickPosition;
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
   * @return 실제로 들어간 행 수
   */
  public int insertDay(Long contestId, LocalDate day, Map<Long, long[]> pathsByCompany) {
    List<Object[]> rows = new ArrayList<>();
    Date sqlDay = Date.valueOf(day);
    pathsByCompany.forEach((companyId, prices) -> {
      for (int tick = 0; tick < prices.length; tick++) {
        rows.add(new Object[] {contestId, companyId, sqlDay, tick, prices[tick]});
      }
    });
    int[] counts = jdbc.batchUpdate("""
        insert into price_ticks (contest_id, company_id, trading_day, tick_no, price)
        values (?, ?, ?, ?, ?)
        on conflict do nothing
        """, rows);
    int inserted = 0;
    for (int c : counts) {
      inserted += Math.max(c, 0); // 드라이버가 개수를 모르면 음수(SUCCESS_NO_INFO)를 준다
    }
    return inserted;
  }

  /** 정확히 그 틱의 가격(주문 체결용). */
  public Optional<Long> priceAt(Long contestId, Long companyId, LocalDate day, int tick) {
    List<Long> found = jdbc.queryForList("""
        select price from price_ticks
         where contest_id = ? and company_id = ? and trading_day = ? and tick_no = ?
        """, Long.class, contestId, companyId, Date.valueOf(day), tick);
    return found.stream().findFirst();
  }

  /**
   * 지점 at 까지(포함) 각 종목의 가장 최근 가격. at 보다 뒤(미래)의 행은 조건에서 빠진다(I11).
   * (trading_day, tick_no) <= (?, ?) 는 "날짜가 앞서거나, 같은 날이면 틱이 같거나 앞선" 행이다.
   * 메모: 대회가 길어지면 이 쿼리가 읽는 행이 늘어난다. 4주차 EXPLAIN 실험 재료로 남겨 둔다.
   */
  public Map<Long, Long> latestPricesUpTo(Long contestId, TickPosition at) {
    Map<Long, Long> prices = new HashMap<>();
    jdbc.query("""
        select distinct on (company_id) company_id, price
          from price_ticks
         where contest_id = ? and (trading_day, tick_no) <= (?, ?)
         order by company_id, trading_day desc, tick_no desc
        """, rs -> {
          prices.put(rs.getLong("company_id"), rs.getLong("price"));
        }, contestId, Date.valueOf(at.day()), at.tick());
    return prices;
  }
}
