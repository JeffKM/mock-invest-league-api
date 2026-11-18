-- 6주차⑩ P7 가입 몰림: 참가 요청이 동시에 몰릴 때 DB 세션이 0.1초마다 무엇을 기다리는지 센다.
-- p7-join.sh 가 참가를 쏘는 동안 같이 돌린다. 기록 길이는 -v cnt=<횟수>(0.1초 단위, 기본 100 = 10초).
-- 열: 시각(밀리초) | 쿼리 중 | 트랜잭션 안에서 쉼(앱이 커넥션을 쥔 채 다른 일) | 행 잠금 대기 | 내부 잠금 대기 | 대기 종류별 세션 수
\if :{?cnt}
\else
\set cnt 100
\endif
select to_char(clock_timestamp() at time zone 'Asia/Seoul', 'HH24:MI:SS.MS')                      as 시각,
       count(*) filter (where state = 'active')                                                   as 쿼리중,
       count(*) filter (where state = 'idle in transaction')                                      as 트랜잭션쉼,
       count(*) filter (where wait_event_type = 'Lock')                                           as 잠금,
       count(*) filter (where wait_event_type = 'LWLock')                                         as lw,
       coalesce((select string_agg(w || '=' || n, ',' order by n desc) from (
         select wait_event_type || ':' || wait_event as w, count(*) as n
           from pg_stat_activity
          where pid <> pg_backend_pid() and backend_type = 'client backend'
            and usename is distinct from 'rdsadmin' and wait_event is not null
            and state <> 'idle'
          group by 1) x), '-')                                                                    as 대기
from pg_stat_activity
where pid <> pg_backend_pid()
  and backend_type = 'client backend'
  and usename is distinct from 'rdsadmin'
\watch i=0.1 c=:cnt
