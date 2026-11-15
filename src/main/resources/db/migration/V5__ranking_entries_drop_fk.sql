-- 6주차⑦ P6: 순위 줄(ranking_entries)의 외래 키 2개를 뺀다.
-- 10만 명 스냅샷 한 장을 쓰는 데 4.6~6.0초 중 2.7~3.0초가 줄마다 하는 외래 키 검사였다(스냅샷 1.6초, 참가자 1.2초).
-- 스냅샷 쪽은 같은 트랜잭션에서 방금 만든 한 줄을 10만 번 확인하고 잠갔다. 빼고 재니 2.1~2.9초(docs/07 10절).
--
-- 빼도 되는 이유: 이 표의 줄은 RankingRepository.INSERT_ENTRIES_SQL 한 문장만 만든다. 그 문장은
-- participants 에서 직접 읽어 같은 트랜잭션에서 만든 스냅샷 id 로 넣으므로, 없는 참가자·스냅샷을 가리킬 수 없다.
-- 지우는 쪽(pruneIntradayBefore)은 줄을 먼저, 스냅샷을 나중에 한 트랜잭션에서 지운다. 참가자는 앱이 지우지 않는다.
-- 대신 불변식 검사 RANKING_ENTRY_REFS 가 "가리키는 참가자가 없거나 다른 대회의 참가자인 줄"을 센다.
alter table ranking_entries
  drop constraint ranking_entries_snapshot_id_fkey,
  drop constraint ranking_entries_participant_id_fkey;
