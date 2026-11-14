-- 6주차⑦ P6: p6-seed.sql 로 만든 대회와 그 데이터를 모두 지운다(외래 키 순서대로). 몇 번 돌려도 같다.
-- 사용: ./psql.sh -f /sql/p6-cleanup.sql
\set ON_ERROR_STOP on
\timing on
begin;
create temp table p6c as select id from contests where name like 'p6-scale-%';
delete from ranking_entries where snapshot_id in (select s.id from ranking_snapshots s where s.contest_id in (select id from p6c));
delete from ranking_snapshots where contest_id in (select id from p6c);
delete from holdings where participant_id in (select id from participants where contest_id in (select id from p6c));
create temp table p6u as select user_id as id from participants where contest_id in (select id from p6c);
delete from participants where contest_id in (select id from p6c);
delete from users where id in (select id from p6u);
delete from price_ticks where contest_id in (select id from p6c);
delete from contest_listings where contest_id in (select id from p6c);
delete from contests where id in (select id from p6c);
commit;
select count(*) as left_contests from contests where name like 'p6-scale-%';
