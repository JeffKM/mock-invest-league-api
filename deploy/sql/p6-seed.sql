-- 6주차⑦ P6: 참가자가 아주 많은 대회를 SQL 로 바로 만든다(k6 로 수만 명을 만들면 준비만 수십 분).
-- 사용(서버 /opt/league): ./psql.sh -v n=50000 -f /sql/p6-seed.sql   → 마지막 줄에 대회 id
--   -v c=<대회 id> 를 주면 대회·상장·가격은 만들지 않고 참가자·보유만 채운다(관리자 API 로 만들고 시작한 대회에 쓴다).
--   상장 표(contest_listings)는 ANALYZE 하지 않는다: 새 대회의 상장 통계가 있는지 없는지가 실험 대상이다(docs/07 10절).
-- 대회는 서버 시계(2026-11-16)에 진행 중이고, 그날 가격 78틱과 참가자마다 보유 종목 약 5개를 넣는다.
-- 원장(ledger)은 넣지 않는다 → 이 대회에는 불변식 검사를 쓰지 않는다. 끝나면 p6-cleanup.sql 로 지운다.
\set ON_ERROR_STOP on
\timing on
begin;
\if :{?c}
\else
insert into contests (name, status, starts_on, ends_on, seed)
  values ('p6-scale-' || :n, 'RUNNING', '2026-11-16', '2026-12-31', :n) returning id as c \gset
insert into contest_listings (contest_id, company_id, base_price)
  select :c, id, base_price from companies;
insert into price_ticks (contest_id, company_id, trading_day, tick_no, price)
  select :c, cl.company_id, date '2026-11-16', t, cl.base_price + ((t * 7 + cl.company_id * 3) % 500)
    from contest_listings cl cross join generate_series(0, 77) t
   where cl.contest_id = :c;
\endif
insert into users (nickname)
  select 'p6-' || :c || '-' || g from generate_series(1, :n) g;
insert into participants (contest_id, user_id, cash)
  select :c, u.id, 5000000 + (u.id % 1000) * 1000   -- 현금을 조금씩 다르게 → 순위가 갈린다
    from users u where u.nickname like 'p6-' || :c || '-%';
insert into holdings (participant_id, company_id, quantity, total_cost)
  select p.id, co.id, 1 + (p.id % 20), (1 + (p.id % 20)) * co.base_price
    from participants p join companies co on (co.id + p.id) % 5 = 0
   where p.contest_id = :c;
commit;
analyze participants; analyze holdings; analyze price_ticks; analyze users;
select :c as contest_id,
       (select count(*) from participants where contest_id = :c) as participants,
       (select count(*) from holdings h join participants p on p.id = h.participant_id where p.contest_id = :c) as holdings;
