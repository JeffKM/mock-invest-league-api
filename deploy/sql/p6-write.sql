-- 6주차⑦ P6 쓰기 절반: 스냅샷 한 장(INSERT ... SELECT)이 어디에 시간을 쓰는지. 끝에 ROLLBACK 해서 아무것도 남기지 않는다.
-- 사용: ./psql.sh -v c=<대회 id> -f /sql/p6-write.sql     (서버 시계 10:00 = 12번 틱)
-- RankingRepository.INSERT_ENTRIES_SQL 과 같은 문장이다. 읽기만 보는 p6-explain.sql 과 나란히 본다.
-- 볼 것: 맨 위 Insert 노드 시간 − 그 아래 읽기 시간 = 줄 쓰기·인덱스 2개 유지,
--        "Trigger for constraint ..." = 줄마다 하는 외래 키 검사(스냅샷·참가자), WAL = 쓰기 기록량.
--   -v nofk=1 을 주면 같은 트랜잭션 안에서 ranking_entries 의 외래 키 2개를 잠시 빼고 잰다(끝의 ROLLBACK 으로 되돌아온다).
--   그동안 ranking_entries 는 잠긴다(ACCESS EXCLUSIVE, 몇 초). 부하가 없을 때만 쓴다.
\set ON_ERROR_STOP on
begin;
\if :{?nofk}
alter table ranking_entries drop constraint if exists ranking_entries_snapshot_id_fkey,
                            drop constraint if exists ranking_entries_participant_id_fkey;  -- V5 뒤에는 이미 없다
\endif
insert into ranking_snapshots (contest_id, trading_day, tick_no) values (:c, date '2026-11-16', 999)
  returning id as s \gset
explain (analyze, buffers, wal, timing)
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
insert into ranking_entries (snapshot_id, participant_id, rank, total_assets)
select :s, t.participant_id, rank() over (order by t.total desc), t.total
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
rollback;
