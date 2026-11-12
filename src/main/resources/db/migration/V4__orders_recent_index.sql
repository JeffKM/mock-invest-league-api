-- 4주차: "내 주문 목록"(최근 50건)의 정렬과 인덱스를 맞춘다.
-- 예전: 쿼리는 order by id desc, 인덱스는 (participant_id, created_at desc) → 정렬과 인덱스 순서가 달랐다.
-- 플래너는 기본키를 뒤에서부터 읽으며 이 참가자 주문을 골라내는 길을 골랐고,
-- 이 참가자의 주문이 오래전에 몰려 있으면 다른 사람 주문 10만 건을 건너뛰었다.
-- 지금: 쿼리를 order by created_at desc, id desc 로 바꾸고 인덱스도 같은 순서로 만든다.
-- 인덱스에서 이 참가자 부분의 앞 50건만 읽고 멈춘다. 결과는 ROADMAP 18단계 실험 C.
drop index idx_orders_participant_created;
create index idx_orders_participant_recent on orders (participant_id, created_at desc, id desc);
