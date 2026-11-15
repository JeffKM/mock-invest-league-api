-- 6주차⑦ P6: p6-seed.sql 로 만든 대회와 그 데이터를 모두 지운다(외래 키 순서대로). 몇 번 돌려도 같다.
-- 사용: ./psql.sh -f /sql/p6-cleanup.sql
\set ON_ERROR_STOP on
\timing on
begin;
-- 참가자·사용자를 지우면 외래 키 검사가 "이 id 를 가리키는 줄이 남았나"를 줄마다 찾는다.
-- ranking_entries 의 기본 키는 (snapshot_id, participant_id), participants 의 유일 키는 (contest_id, user_id)라
-- participant_id·user_id 만으로는 인덱스를 못 탄다 → 지우는 줄마다 표 전체를 훑는다(26만 명에서 끝나지 않았다).
-- 앱은 참가자를 지우지 않으므로 스키마에 넣지 않고, 지우는 동안만 인덱스를 만들었다가 같은 트랜잭션에서 버린다.
create index p6c_ranking_entries_participant on ranking_entries (participant_id);
create index p6c_participants_user on participants (user_id);
create temp table p6c as select id from contests where name like 'p6-scale-%';
delete from ranking_entries where snapshot_id in (select s.id from ranking_snapshots s where s.contest_id in (select id from p6c));
delete from ranking_snapshots where contest_id in (select id from p6c);
delete from holdings where participant_id in (select id from participants where contest_id in (select id from p6c));
create temp table p6u as select user_id as id from participants where contest_id in (select id from p6c);
delete from participants where contest_id in (select id from p6c);
delete from users where id in (select id from p6u);
delete from price_ticks where contest_id in (select id from p6c);
delete from disclosures where contest_id in (select id from p6c);  -- API 로 시작한 대회는 공시도 만든다
delete from contest_listings where contest_id in (select id from p6c);
delete from contests where id in (select id from p6c);
drop index p6c_ranking_entries_participant;
drop index p6c_participants_user;
commit;
select count(*) as left_contests from contests where name like 'p6-scale-%';
