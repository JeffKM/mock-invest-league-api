-- 3주차③: 가상 공시를 가격 생성과 함께 만들고, 순위 스냅샷을 저장한다.

-- 공시도 가격처럼 "결정적으로" 만든다(같은 시드 → 같은 공시).
-- seq 는 그날 몇 번째 공시인지. 배치를 두 번 돌려도 (대회, 날짜, seq) 유니크가 두 번째 INSERT 를 막는다.
alter table disclosures add column seq int not null default 0;
alter table disclosures add constraint uq_disclosures_contest_day_seq unique (contest_id, trading_day, seq);
-- 회사 공시는 섹터가 없고, 섹터 공시는 회사가 없다. 둘 다 없으면 시장 전체 공시다
alter table disclosures add constraint ck_disclosures_scope check (company_id is null or sector is null);

-- 순위 스냅샷 한 장 = "어느 대회의 어느 틱 기준 순위". 같은 틱을 두 번 찍지 않는다
create table ranking_snapshots (
  id           bigserial primary key,
  contest_id   bigint not null references contests (id),
  trading_day  date   not null,
  tick_no      int    not null,
  created_at   timestamptz not null default now(),
  unique (contest_id, trading_day, tick_no)
);

-- 스냅샷의 한 줄 = 참가자 한 명의 그 시점 총자산과 순위
create table ranking_entries (
  snapshot_id     bigint not null references ranking_snapshots (id),
  participant_id  bigint not null references participants (id),
  rank            int    not null check (rank >= 1),
  total_assets    bigint not null check (total_assets >= 0),
  primary key (snapshot_id, participant_id)
);
-- 순위표 조회(상위 N명)를 인덱스 순서대로 읽기 위함
create index idx_ranking_entries_snapshot_rank on ranking_entries (snapshot_id, rank);
