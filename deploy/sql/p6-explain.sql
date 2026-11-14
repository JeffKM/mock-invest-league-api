-- 6주차⑦ P6: 스냅샷의 총자산 계산(읽기 부분)이 어떻게 실행되는지. 쓰기 없이 SELECT 만 EXPLAIN ANALYZE 한다.
-- 사용: ./psql.sh -v c=<대회 id> -f /sql/p6-explain.sql     (서버 시계 10:00 = 12번 틱)
-- RankingRepository.INSERT_ENTRIES_SQL 의 select 부분과 같은 문장이다.
\set ON_ERROR_STOP on
explain (analyze, buffers, timing)
with px as (
  select cl.company_id, p.price, p.trading_day, p.tick_no
    from contest_listings cl
    cross join lateral (
      select pt.price, pt.trading_day, pt.tick_no
        from price_ticks pt
       where pt.contest_id = cl.contest_id and pt.company_id = cl.company_id
         and (pt.trading_day, pt.tick_no) <= (date '2026-11-16', 12)
       order by pt.trading_day desc, pt.tick_no desc
       limit 1
    ) p
   where cl.contest_id = :c
)
select t.participant_id, rank() over (order by t.total desc), t.total
  from (
    select p.id as participant_id,
           (p.cash + p.reserved_cash
             + coalesce(sum(h.quantity * coalesce(px.price, cl.base_price)), 0))::bigint as total
      from participants p
      left join holdings h on h.participant_id = p.id and h.quantity > 0
      left join contest_listings cl on cl.contest_id = p.contest_id and cl.company_id = h.company_id
      left join px on px.company_id = h.company_id
     where p.contest_id = :c
     group by p.id
  ) t;
