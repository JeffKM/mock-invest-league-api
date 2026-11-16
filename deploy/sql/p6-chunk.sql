-- 6주차⑦ P6 시도 ②의 시험판: 스냅샷 한 장을 "한 문장"으로 찍을 때와 "나눠서, 사이사이 쉬며" 찍을 때를 비교한다.
-- 앱 코드를 바꾸기 전에 SQL 만으로 효과를 먼저 본다. 효과가 있으면 같은 방식을 앱(자바)으로 옮긴다.
-- 사용: ./psql.sh -v c=<대회> -v t=<틱 번호> -v chunked=0|1 [-v n=10000] [-v pause=0.2] -f /sql/p6-chunk.sql </dev/null
--   스냅샷 머리를 (대회, 2026-11-16, t) 로 새로 만들고 채운다. 지우는 것은 부르는 쪽(p6-concurrent.sh)이 한다.
--   가격은 서버 시계(10:00 = 12번 틱) 기준으로 고정한다. 계산식은 RankingRepository.INSERT_ENTRIES_SQL 과 같다.
--
-- 나눠 찍기(chunked=1)는 두 단계다. 순위는 전체를 줄 세워야 매겨지기 때문이다.
--   1단계: 참가자를 n 명씩 끊어 총자산만 계산해 p6_totals(UNLOGGED: WAL 을 쓰지 않는 작업용 표)에 넣는다.
--   2단계: p6_totals 를 총자산 큰 순서로 n 줄씩 꺼내 순위를 매겨 ranking_entries 에 넣는다.
--          구간이 바뀌어도 순위가 이어지게 "지금까지 넣은 줄 수·마지막 총자산·마지막 순위"를 들고 간다.
--          이번 구간의 순위 = 앞 구간 줄 수 + 구간 안 rank(). 단 앞 구간 마지막과 총자산이 같으면 그 순위를 그대로(동점).
--   구간마다 COMMIT 하고 pause 초 쉰다 → DB 를 한 번에 쥐는 시간이 짧아지고, 그 틈에 다른 요청이 돈다.
--   (앱으로 옮길 때는 다 넣기 전의 스냅샷이 조회에 보이지 않게 "완료" 표시가 필요하다. 시험판은 조회하지 않는 틱 번호를 쓴다.)
\set ON_ERROR_STOP on
\if :{?n}
\else
\set n 10000
\endif
\if :{?pause}
\else
\set pause 0.2
\endif

create unlogged table if not exists p6_totals (
  snapshot_id bigint not null, participant_id bigint not null, total bigint not null);
create index if not exists p6_totals_order on p6_totals (snapshot_id, total desc, participant_id desc);

insert into ranking_snapshots (contest_id, trading_day, tick_no) values (:c, date '2026-11-16', :t)
  returning id as s \gset

\if :chunked
select set_config('p6.c', :'c', false), set_config('p6.s', :'s', false),
       set_config('p6.n', :'n', false), set_config('p6.pause', :'pause', false) \g /dev/null
\timing on
do $$
declare
  c bigint := current_setting('p6.c')::bigint;
  s bigint := current_setting('p6.s')::bigint;
  n int := current_setting('p6.n')::int;
  pause float8 := current_setting('p6.pause')::float8;
  lo bigint;
  hi bigint;
  seen int := 0;
  last_total bigint;
  last_pid bigint;
  last_rank int;
  cnt int;
begin
  -- 1단계: 참가자 id 순서로 n 명씩
  select min(id) into lo from participants where contest_id = c;
  while lo is not null loop
    select id into hi from participants where contest_id = c and id >= lo order by id offset n limit 1;
    insert into p6_totals (snapshot_id, participant_id, total)
    with px as (
      select cl.company_id, p.price
        from contest_listings cl
        cross join lateral (
          select pt.price from price_ticks pt
           where pt.contest_id = cl.contest_id and pt.company_id = cl.company_id
             and (pt.trading_day, pt.tick_no) <= (date '2026-11-16', 12)
           order by pt.trading_day desc, pt.tick_no desc
           limit 1
        ) p
       where cl.contest_id = c
    )
    select s, p.id,
           (p.cash + p.reserved_cash + coalesce(sum(h.quantity * coalesce(px.price, cl.base_price)), 0))::bigint
      from participants p
      left join holdings h on h.participant_id = p.id and h.quantity > 0
      left join contest_listings cl on cl.contest_id = p.contest_id and cl.company_id = h.company_id
      left join px on px.company_id = h.company_id
     where p.contest_id = c and p.id >= lo and (hi is null or p.id < hi)
     group by p.id;
    commit;
    perform pg_sleep(pause);
    lo := hi;
  end loop;

  -- 2단계: 총자산 큰 순서로 n 줄씩 순위를 매겨 넣는다(같으면 participant_id 큰 쪽부터 꺼내는 것은 구간을 이어 붙이기 위한 순서일 뿐)
  loop
    with ch as (
      select participant_id, total from p6_totals
       where snapshot_id = s
         and (last_total is null or (total, participant_id) < (last_total, last_pid))
       order by total desc, participant_id desc
       limit n
    ), ins as (
      insert into ranking_entries (snapshot_id, participant_id, rank, total_assets)
      select s, participant_id,
             (case when total = last_total then last_rank else seen + rank() over (order by total desc) end)::int,
             total
        from ch
      returning participant_id, rank, total_assets
    )
    select count(*), min(total_assets), max(rank),
           (array_agg(participant_id order by total_assets, participant_id))[1]
      into cnt, last_total, last_rank, last_pid
      from ins;
    exit when cnt = 0;
    seen := seen + cnt;
    commit;
    perform pg_sleep(pause);
  end loop;

  delete from p6_totals where snapshot_id = s;
  commit;
end $$;
\timing off
\else
\timing on
with px as (
  select cl.company_id, p.price
    from contest_listings cl
    cross join lateral (
      select pt.price from price_ticks pt
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
           (p.cash + p.reserved_cash + coalesce(sum(h.quantity * coalesce(px.price, cl.base_price)), 0))::bigint as total
      from participants p
      left join holdings h on h.participant_id = p.id and h.quantity > 0
      left join contest_listings cl on cl.contest_id = p.contest_id and cl.company_id = h.company_id
      left join px on px.company_id = h.company_id
     where p.contest_id = :c
     group by p.id
  ) t;
\timing off
\endif
