package com.jeffkm.mockleague.contest;

import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 불변식 검사(6주차③). 부하 테스트가 끝난 뒤 "돈과 주식이 한 원이라도 어긋났는가"를 DB 에서 직접 센다.
 *
 * <p>응답이 200 이었다고 데이터가 맞는 것은 아니다. 동시에 들어온 요청 둘이 같은 돈을 두 번 쓰거나,
 * 취소와 체결이 겹쳐 묶인 돈이 풀리지 않는 일은 HTTP 상태로는 보이지 않는다. 그래서 결과를 장부와 대조한다.
 * 각 검사는 "어긋난 행"을 찾는 SQL 이고, 0건이어야 통과다.
 *
 * <p>스냅샷 격리(REPEATABLE READ, 읽기 전용)로 돌려 모든 검사가 같은 순간의 데이터를 본다.
 * 트래픽 중에 돌려도 검사끼리 서로 다른 시점을 보고 거짓 경보를 내지 않는다.
 */
@Service
public class InvariantAuditService {

  /** 어긋난 행의 id 를 이만큼만 예시로 돌려준다(전부 돌려주면 응답이 커진다). */
  private static final int SAMPLE = 5;

  private final JdbcTemplate jdbc;

  public InvariantAuditService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Check(String code, String rule, long violations, List<Long> sample) {
  }

  public record Report(Long contestId, boolean ok, List<Check> checks) {
  }

  private record Rule(String code, String rule, String sql) {
  }

  // 모든 SQL 은 "어긋난 행의 id" 를 돌려준다. ? 는 모두 대회 id 다(개수만큼 같은 값을 넣는다)
  private static final List<Rule> RULES = List.of(
      new Rule("I5_CASH_EQUALS_LEDGER", "참가자 현금 + 묶인 현금 = 원장 합계",
          """
          select p.id from participants p
          left join (select l.participant_id, sum(l.amount) s from cash_ledger l
                     join participants p2 on p2.id = l.participant_id where p2.contest_id = ?
                     group by l.participant_id) l on l.participant_id = p.id
          where p.contest_id = ? and p.cash + p.reserved_cash <> coalesce(l.s, 0)
          order by p.id"""),
      new Rule("RESERVED_CASH_EQUALS_PENDING_BUYS", "묶인 현금 = 대기 중인 지정가 매수의 (지정가 × 수량 + 수수료) 합",
          """
          select p.id from participants p
          join contests c on c.id = p.contest_id
          left join (select o.participant_id,
                            sum(o.limit_price * o.quantity + (o.limit_price * o.quantity * c2.fee_bp) / 10000) s
                     from orders o join participants p2 on p2.id = o.participant_id
                     join contests c2 on c2.id = p2.contest_id
                     where p2.contest_id = ? and o.status = 'PENDING' and o.side = 'BUY'
                     group by o.participant_id) r on r.participant_id = p.id
          where p.contest_id = ? and p.reserved_cash <> coalesce(r.s, 0)
          order by p.id"""),
      new Rule("RESERVED_QTY_EQUALS_PENDING_SELLS", "묶인 주식 수 = 대기 중인 지정가 매도 수량 합(종목별)",
          """
          select h.participant_id from holdings h
          join participants p on p.id = h.participant_id
          left join (select o.participant_id, o.company_id, sum(o.quantity) q
                     from orders o join participants p2 on p2.id = o.participant_id
                     where p2.contest_id = ? and o.status = 'PENDING' and o.side = 'SELL'
                     group by o.participant_id, o.company_id) r
                 on r.participant_id = h.participant_id and r.company_id = h.company_id
          where p.contest_id = ? and h.reserved_quantity <> coalesce(r.q, 0)
          order by h.participant_id"""),
      new Rule("HOLDING_EQUALS_FILLED_NET", "보유 수량 = 체결된 매수 수량 − 체결된 매도 수량(종목별)",
          """
          select coalesce(h.participant_id, n.participant_id) from
            (select h.* from holdings h join participants p on p.id = h.participant_id where p.contest_id = ?) h
          full join
            (select o.participant_id, o.company_id,
                    sum(case when o.side = 'BUY' then o.quantity else -o.quantity end) q
             from orders o join participants p on p.id = o.participant_id
             where p.contest_id = ? and o.status = 'FILLED'
             group by o.participant_id, o.company_id) n
          on n.participant_id = h.participant_id and n.company_id = h.company_id
          where coalesce(h.quantity, 0) <> coalesce(n.q, 0)
          order by 1"""),
      new Rule("ORDER_LEDGER_ONCE", "체결된 주문은 원장에 거래 1줄(+수수료 1줄)로 정확히 한 번, 체결 안 된 주문은 0줄",
          """
          select o.id from orders o
          join participants p on p.id = o.participant_id
          left join (select l.order_id, count(*) n, sum(l.amount) s from cash_ledger l
                     join participants p2 on p2.id = l.participant_id
                     where p2.contest_id = ? and l.order_id is not null
                     group by l.order_id) l on l.order_id = o.id
          where p.contest_id = ? and (
            (o.status = 'FILLED' and (
               coalesce(l.n, 0) <> case when o.fee > 0 then 2 else 1 end
               or coalesce(l.s, 0) <> case when o.side = 'BUY' then -(o.filled_price * o.quantity + o.fee)
                                           else o.filled_price * o.quantity - o.fee end))
            or (o.status <> 'FILLED' and coalesce(l.n, 0) <> 0))
          order by o.id"""),
      new Rule("I10_FILL_FIELDS_MATCH_STATUS", "체결 정보(가격·수수료·틱)는 FILLED 주문에만, 빠짐없이 있다",
          """
          select o.id from orders o join participants p on p.id = o.participant_id
          where p.contest_id = ? and (
            (o.status = 'FILLED' and (o.filled_price is null or o.fee is null or o.filled_tick is null
                                      or o.filled_at is null))
            or (o.status <> 'FILLED' and (o.filled_price is not null or o.fee is not null
                                          or o.filled_tick is not null or o.filled_at is not null)))
          order by o.id"""));

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Report audit(Long contestId) {
    List<Check> checks = RULES.stream().map(r -> run(r, contestId)).toList();
    return new Report(contestId, checks.stream().allMatch(c -> c.violations() == 0), checks);
  }

  private Check run(Rule rule, Long contestId) {
    long params = rule.sql().chars().filter(ch -> ch == '?').count();
    Object[] args = new Object[(int) params];
    Arrays.fill(args, contestId);
    List<Long> ids = jdbc.queryForList(rule.sql(), Long.class, args);
    return new Check(rule.code(), rule.rule(), ids.size(), ids.stream().distinct().limit(SAMPLE).toList());
  }
}
