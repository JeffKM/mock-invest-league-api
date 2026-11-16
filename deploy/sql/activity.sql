-- 6주차③ 매분 튐 조사: 1초마다 DB 안에서 무슨 일이 있는지 한 줄로 센다(150초 = 매분 경계를 두 번 이상 지난다)
-- 부하를 거는 동안 같이 돌린다:  ./psql.sh -tA -F ' ' -f /sql/activity.sql | tee /tmp/activity.txt
-- 기록 길이(초)는 -v cnt=<초> 로 바꾼다(기본 150).
-- 열: 시각 | 앱 활성 세션 | 행 잠금 대기 | 디스크 대기 | 내부 잠금(LWLock) 대기 | autovacuum | rdsadmin(RDS 관리) 활성 | 대기 종류
\if :{?cnt}
\else
\set cnt 150
\endif
select to_char(now() at time zone 'Asia/Seoul', 'HH24:MI:SS')                                     as 시각,
       count(*) filter (where state = 'active' and usename is distinct from 'rdsadmin'
                        and backend_type = 'client backend')                                     as 앱,
       count(*) filter (where wait_event_type = 'Lock')                                          as 잠금,
       count(*) filter (where wait_event_type = 'IO')                                            as io,
       count(*) filter (where wait_event_type = 'LWLock')                                        as lw,
       count(*) filter (where backend_type = 'autovacuum worker')                                as av,
       count(*) filter (where usename = 'rdsadmin' and state = 'active')                         as rds,
       coalesce(string_agg(distinct wait_event_type || ':' || wait_event, ','), '-')            as 대기
from pg_stat_activity
where pid <> pg_backend_pid()
  and backend_type in ('client backend', 'autovacuum worker')
\watch i=1 c=:cnt
