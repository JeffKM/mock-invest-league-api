-- 캐시 비교용 데이터(scripts/cache-bench.sh 가 빈 DB league_perf 에 넣는다). 운영 DB 에 넣지 않는다.
-- 대회 1개가 30일째(2027-01-30), 종목 24개 × 78틱 × 30일 가격, 참가자 1,000명(보유 약 5종목), 순위 스냅샷 1장.
insert into contests (name, status, starts_on, ends_on, seed)
values ('부하 측정', 'RUNNING', date '2027-01-01', date '2027-01-30', 42);

insert into contest_listings (contest_id, company_id, base_price)
select c.id, co.id, co.base_price from contests c cross join companies co;

insert into price_ticks (contest_id, company_id, trading_day, tick_no, price)
select cl.contest_id, cl.company_id, d::date, t,
       cl.base_price + ((extract(doy from d)::int * 13 + t * 7 + cl.company_id::int * 3) % 500)
  from contest_listings cl
  cross join generate_series(date '2027-01-01', date '2027-01-30', interval '1 day') d
  cross join generate_series(0, 77) t;

insert into users (nickname) select 'load-' || g from generate_series(1, 1000) g;
insert into participants (contest_id, user_id, cash)
select (select id from contests), u.id, 5000000 from users u;
insert into holdings (participant_id, company_id, quantity, total_cost)
select p.id, co.id, 10, 10 * co.base_price from participants p join companies co on (co.id + p.id) % 5 = 0;
insert into cash_ledger (participant_id, entry_type, amount) select id, 'INITIAL', 5000000 from participants;

-- 2027-01-30 16번 틱(10:20) 기준 순위 스냅샷. 앱의 RankingRepository 와 같은 계산
insert into ranking_snapshots (contest_id, trading_day, tick_no) values ((select id from contests), date '2027-01-30', 16);
with px as (
  select cl.company_id, p.price
    from contest_listings cl
    cross join lateral (
      select pt.price from price_ticks pt
       where pt.contest_id = cl.contest_id and pt.company_id = cl.company_id
         and (pt.trading_day, pt.tick_no) <= (date '2027-01-30', 16)
       order by pt.trading_day desc, pt.tick_no desc limit 1) p
)
insert into ranking_entries (snapshot_id, participant_id, rank, total_assets)
select (select max(id) from ranking_snapshots), t.participant_id, rank() over (order by t.total desc), t.total
  from (select p.id as participant_id,
               (p.cash + p.reserved_cash + coalesce(sum(h.quantity * px.price), 0))::bigint as total
          from participants p
          left join holdings h on h.participant_id = p.id
          left join px on px.company_id = h.company_id
         group by p.id) t;
analyze;
