-- 6주차⑫ N3: 1초마다 DB 세션 상태와, 잠금을 기다리는 세션이 있으면 그 잠금을 쥔 쪽이 무엇인지 남긴다(stall-watch.sh 가 부른다).
-- 기록 길이(초)는 -v cnt=<초>(기본 600).
-- 열: 시각(한국) | 앱 활성 | 행·표 잠금 대기 | 디스크 대기 | 내부 잠금 대기 | autovacuum | 대기 종류 | 잠금 쥔 쪽(autovacuum 이면 그 작업) | autovacuum 이 하는 일
\if :{?cnt}
\else
\set cnt 600
\endif
select to_char(now() at time zone 'Asia/Seoul', 'HH24:MI:SS')                                   as 시각,
       count(*) filter (where a.state = 'active' and a.backend_type = 'client backend'
                        and a.usename is distinct from 'rdsadmin')                               as 앱,
       count(*) filter (where a.wait_event_type = 'Lock')                                         as 잠금,
       count(*) filter (where a.wait_event_type = 'IO')                                           as io,
       count(*) filter (where a.wait_event_type = 'LWLock')                                       as lw,
       count(*) filter (where a.backend_type = 'autovacuum worker')                               as av,
       coalesce(string_agg(distinct a.wait_event_type || ':' || a.wait_event, ','), '-')         as 대기,
       coalesce((select string_agg(distinct b.backend_type || '[' || coalesce(b.state, '') || ']:'
                                   || left(regexp_replace(b.query, '\s+', ' ', 'g'), 60), ' | ')
                   from pg_stat_activity w
                   cross join lateral unnest(pg_blocking_pids(w.pid)) as bp(pid)
                   join pg_stat_activity b on b.pid = bp.pid
                  where w.wait_event_type = 'Lock'), '-')                                         as 잠금쥔쪽,
       coalesce((select string_agg(left(regexp_replace(v.query, '\s+', ' ', 'g'), 50), ' | ')
                   from pg_stat_activity v where v.backend_type = 'autovacuum worker'), '-')       as 오토배큠
from pg_stat_activity a
where a.pid <> pg_backend_pid()
  and a.backend_type in ('client backend', 'autovacuum worker')
\watch i=1 c=:cnt
