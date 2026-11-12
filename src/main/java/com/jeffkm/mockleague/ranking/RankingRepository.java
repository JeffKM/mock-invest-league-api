package com.jeffkm.mockleague.ranking;

import com.jeffkm.mockleague.contest.TickPosition;
import com.jeffkm.mockleague.market.PriceTickRepository;
import java.io.Serializable;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 순위 스냅샷 저장·조회. 참가자 1,000명의 총자산을 자바로 하나씩 계산해 넣지 않고,
 * INSERT ... SELECT 한 문장으로 DB 안에서 계산·정렬·저장한다(왕복 1번).
 *
 * <p>모듈 경계 메모: 순위는 여러 테이블(participants·holdings·price_ticks)을 가로지르는 "읽기 모델"이다.
 * 다른 패키지의 리포지토리를 부르는 대신, 이 클래스의 SQL 이 테이블을 직접 읽는다(쓰기는 자기 테이블에만 한다).
 */
@Repository
public class RankingRepository {

  public record Snapshot(Long id, LocalDate tradingDay, int tickNo, int participants) {}

  /** Serializable: 순위표 캐시(Redis)가 바이트로 바꿔 저장한다. */
  public record Entry(int rank, Long participantId, String nickname, long totalAssets) implements Serializable {}

  private final JdbcTemplate jdbc;

  public RankingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 스냅샷 머리 행을 만든다. 같은 (대회, 날짜, 틱)이 이미 있으면 아무것도 하지 않고 비어 있음을 돌려준다.
   * 배치가 두 번 돌거나 서버 두 대가 동시에 돌아도 스냅샷은 한 장만 생긴다.
   */
  public Optional<Long> createSnapshot(Long contestId, TickPosition at) {
    List<Long> ids = jdbc.queryForList("""
        insert into ranking_snapshots (contest_id, trading_day, tick_no) values (?, ?, ?)
        on conflict (contest_id, trading_day, tick_no) do nothing
        returning id
        """, Long.class, contestId, Date.valueOf(at.day()), at.tick());
    return ids.stream().findFirst();
  }

  /**
   * 그 시점 총자산(F12) = 현금 + 묶인 현금 + Σ(보유 수량 × at 시점 가격)을 계산해 순위와 함께 넣는다.
   * <ul>
   *   <li>보유 수량(quantity)은 지정가 매도로 묶인 수량을 포함한다. 묶여도 아직 내 주식이다.</li>
   *   <li>가격은 at 까지의 최신 틱(미래 가격 제외, I11). 틱이 없으면 상장 기준가.</li>
   *   <li>rank(): 총자산이 같으면 같은 순위(1, 2, 2, 4).</li>
   *   <li>한 문장이라 READ COMMITTED 에서도 모든 참가자를 같은 순간의 데이터로 본다.
   *       참가자마다 따로 읽으면 그 사이 체결된 주문 때문에 사람마다 다른 시점으로 평가된다.</li>
   * </ul>
   *
   * @return 넣은 줄 수(= 참가자 수)
   */
  public int fillEntries(Long snapshotId, Long contestId, TickPosition at) {
    return jdbc.update(INSERT_ENTRIES_SQL, Date.valueOf(at.day()), at.tick(), contestId, snapshotId, contestId);
  }

  /**
   * 파라미터 순서: (day, tick, contestId) ← 가격 CTE, 그다음 (snapshotId, contestId).
   * 가격은 현재가 조회와 같은 문장(PriceTickRepository.LATEST_PRICES_SQL)을 CTE 로 재사용한다.
   */
  public static final String TOTALS_SQL = """
      select p.id as participant_id,
             (p.cash + p.reserved_cash
               + coalesce(sum(h.quantity * coalesce(px.price, cl.base_price)), 0))::bigint as total
        from participants p
        left join holdings h on h.participant_id = p.id and h.quantity > 0
        left join contest_listings cl on cl.contest_id = p.contest_id and cl.company_id = h.company_id
        left join px on px.company_id = h.company_id
       where p.contest_id = ?
       group by p.id
      """;

  static final String INSERT_ENTRIES_SQL = "with px as (" + PriceTickRepository.LATEST_PRICES_SQL + ")\n"
      + """
      insert into ranking_entries (snapshot_id, participant_id, rank, total_assets)
      select ?, t.participant_id, rank() over (order by t.total desc), t.total
        from (""" + TOTALS_SQL + ") t";

  /** 가장 최근 스냅샷(틱 기준). */
  public Optional<Snapshot> latestSnapshot(Long contestId) {
    return jdbc.query("""
        select s.id, s.trading_day, s.tick_no,
               (select count(*) from ranking_entries e where e.snapshot_id = s.id) as participants
          from ranking_snapshots s
         where s.contest_id = ?
         order by s.trading_day desc, s.tick_no desc
         limit 1
        """, (rs, i) -> new Snapshot(rs.getLong("id"), rs.getObject("trading_day", LocalDate.class),
            rs.getInt("tick_no"), rs.getInt("participants")), contestId).stream().findFirst();
  }

  /** 상위 limit 명. 같은 순위끼리는 먼저 참가한 사람(id 작은 쪽)부터 보여 준다. */
  public List<Entry> top(Long snapshotId, int limit) {
    return jdbc.query("""
        select e.rank, e.participant_id, u.nickname, e.total_assets
          from ranking_entries e
          join participants p on p.id = e.participant_id
          join users u on u.id = p.user_id
         where e.snapshot_id = ?
         order by e.rank, e.participant_id
         limit ?
        """, (rs, i) -> toEntry(rs), snapshotId, limit);
  }

  /** 스냅샷에서 이 사용자의 줄. 스냅샷 뒤에 참가했으면 없다. */
  public Optional<Entry> entryOfUser(Long snapshotId, Long userId) {
    return jdbc.query("""
        select e.rank, e.participant_id, u.nickname, e.total_assets
          from ranking_entries e
          join participants p on p.id = e.participant_id
          join users u on u.id = p.user_id
         where e.snapshot_id = ? and p.user_id = ?
        """, (rs, i) -> toEntry(rs), snapshotId, userId).stream().findFirst();
  }

  public boolean isParticipant(Long contestId, Long userId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists (select 1 from participants where contest_id = ? and user_id = ?)",
        Boolean.class, contestId, userId));
  }

  private static Entry toEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Entry(rs.getInt("rank"), rs.getLong("participant_id"), rs.getString("nickname"),
        rs.getLong("total_assets"));
  }
}
