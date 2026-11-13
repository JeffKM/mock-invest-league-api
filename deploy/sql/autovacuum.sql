-- 6주차③ 매분 튐 조사: autovacuum 이 언제, 어느 테이블을 몇 번 치웠나
show autovacuum_naptime;
show autovacuum_vacuum_threshold;
show autovacuum_vacuum_scale_factor;
select relname,
       n_live_tup                                   as 살아있는_행,
       n_dead_tup                                   as 죽은_행,
       autovacuum_count                             as 자동vacuum_횟수,
       autoanalyze_count                            as 자동analyze_횟수,
       to_char(last_autovacuum  at time zone 'Asia/Seoul', 'MM-DD HH24:MI:SS') as 마지막_vacuum,
       to_char(last_autoanalyze at time zone 'Asia/Seoul', 'MM-DD HH24:MI:SS') as 마지막_analyze
from pg_stat_user_tables
order by autovacuum_count + autoanalyze_count desc
limit 10;
