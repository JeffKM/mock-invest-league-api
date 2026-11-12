package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.company.Sector;
import com.jeffkm.mockleague.contest.TickPosition;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 공시 저장소. 가격처럼 한 번 쓰면 바뀌지 않는 행이라 JdbcTemplate 으로 다룬다. */
@Repository
public class DisclosureRepository {

  /** 조회 결과 한 줄. 충격 크기(impact_bp)는 담지 않는다: 참가자가 보면 안 되는 "정답"이다. */
  public record Row(Long id, LocalDate tradingDay, int tickNo, Long companyId, String ticker, String companyName,
      Sector sector, String title) {}

  private final JdbcTemplate jdbc;

  public DisclosureRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 하루치 공시를 넣는다. (대회, 날짜, seq) 유니크 + ON CONFLICT DO NOTHING → 두 번 돌려도 한 벌만 남는다. */
  public void insertDay(Long contestId, LocalDate day, List<DisclosurePlanner.Planned> plans) {
    if (plans.isEmpty()) {
      return;
    }
    Date sqlDay = Date.valueOf(day);
    jdbc.batchUpdate("""
        insert into disclosures (contest_id, company_id, sector, trading_day, tick_no, seq, title, impact_bp)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (contest_id, trading_day, seq) do nothing
        """, plans.stream().map(p -> new Object[] {
            contestId, p.companyId(), p.sector() == null ? null : p.sector().name(),
            sqlDay, p.tick(), p.seq(), p.title(), p.impactBp()}).toList());
  }

  /**
   * 지점 at 까지(포함) 공개된 공시, 최신순. 미래 틱의 공시는 조건에서 빠진다(I11).
   * 가격 조회(latestPricesUpTo)와 같은 행 비교 조건을 쓴다: 가격과 공시가 같은 기준으로 "지금"을 자른다.
   */
  public List<Row> visibleUpTo(Long contestId, TickPosition at, int limit) {
    return jdbc.query("""
        select d.id, d.trading_day, d.tick_no, d.company_id, c.ticker, c.name, d.sector, d.title
          from disclosures d
          left join companies c on c.id = d.company_id
         where d.contest_id = ? and (d.trading_day, d.tick_no) <= (?, ?)
         order by d.trading_day desc, d.tick_no desc, d.seq desc
         limit ?
        """, (rs, i) -> {
          String sector = rs.getString("sector");
          Long companyId = rs.getObject("company_id", Long.class);
          return new Row(rs.getLong("id"), rs.getObject("trading_day", LocalDate.class), rs.getInt("tick_no"),
              companyId, rs.getString("ticker"), rs.getString("name"),
              sector == null ? null : Sector.valueOf(sector), rs.getString("title"));
        }, contestId, Date.valueOf(at.day()), at.tick(), limit);
  }
}
