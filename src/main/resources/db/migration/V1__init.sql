-- 초기 스키마. 설계 설명은 docs/03-ERD.md
-- 금액은 모두 원 단위 정수(bigint), 비율은 bp(1bp = 0.01%) 정수

create table users (
  id          bigserial primary key,
  nickname    varchar(30) not null unique,
  created_at  timestamptz not null default now()
);

create table companies (
  id           bigserial primary key,
  ticker       varchar(8)  not null unique,
  name         varchar(50) not null unique,
  sector       varchar(20) not null,
  volatility   varchar(10) not null check (volatility in ('LOW', 'MID', 'HIGH')),
  base_price   bigint      not null check (base_price > 0),
  description  varchar(200)
);

create table contests (
  id                bigserial primary key,
  name              varchar(100) not null,
  status            varchar(20)  not null check (status in ('SCHEDULED', 'RUNNING', 'FINISHED')),
  starts_on         date    not null,
  ends_on           date    not null,
  open_time         time    not null default '09:00',
  close_time        time    not null default '15:30',
  tick_seconds      int     not null default 300 check (tick_seconds > 0),
  initial_cash      bigint  not null default 10000000 check (initial_cash > 0),
  fee_bp            int     not null default 50   check (fee_bp between 0 and 10000),
  price_limit_bp    int     not null default 3000 check (price_limit_bp between 0 and 10000),
  attendance_bonus  bigint  not null default 50000 check (attendance_bonus >= 0),
  seed              bigint  not null,
  created_at        timestamptz not null default now(),
  check (starts_on <= ends_on),
  check (open_time < close_time)
);

create table contest_listings (
  contest_id  bigint not null references contests (id),
  company_id  bigint not null references companies (id),
  base_price  bigint not null check (base_price > 0),
  primary key (contest_id, company_id)
);

create table price_ticks (
  contest_id   bigint not null,
  company_id   bigint not null,
  trading_day  date   not null,
  tick_no      int    not null check (tick_no >= 0),
  price        bigint not null check (price > 0),
  primary key (contest_id, company_id, trading_day, tick_no),
  foreign key (contest_id, company_id) references contest_listings (contest_id, company_id)
);

create table disclosures (
  id           bigserial primary key,
  contest_id   bigint not null references contests (id),
  company_id   bigint references companies (id),   -- null 이면 섹터/시장 전체 공시
  sector       varchar(20),                         -- null 이면 시장 전체
  trading_day  date   not null,
  tick_no      int    not null,
  title        varchar(200) not null,
  body         text,
  impact_bp    int    not null
);
create index idx_disclosures_contest_day on disclosures (contest_id, trading_day, tick_no);

create table participants (
  id             bigserial primary key,
  contest_id     bigint not null references contests (id),
  user_id        bigint not null references users (id),
  cash           bigint not null check (cash >= 0),
  reserved_cash  bigint not null default 0 check (reserved_cash >= 0),
  version        bigint not null default 0,
  joined_at      timestamptz not null default now(),
  unique (contest_id, user_id)
);

create table holdings (
  participant_id     bigint not null references participants (id),
  company_id         bigint not null references companies (id),
  quantity           bigint not null default 0 check (quantity >= 0),
  reserved_quantity  bigint not null default 0 check (reserved_quantity >= 0),
  total_cost         bigint not null default 0 check (total_cost >= 0),
  version            bigint not null default 0,
  primary key (participant_id, company_id),
  check (reserved_quantity <= quantity)
);

create table orders (
  id               bigserial primary key,
  participant_id   bigint not null references participants (id),
  company_id       bigint not null references companies (id),
  side             varchar(4)  not null check (side in ('BUY', 'SELL')),
  order_type       varchar(6)  not null check (order_type in ('MARKET', 'LIMIT')),
  quantity         bigint not null check (quantity > 0),
  limit_price      bigint check (limit_price > 0),
  status           varchar(10) not null check (status in ('PENDING', 'FILLED', 'CANCELED', 'EXPIRED', 'REJECTED')),
  filled_price     bigint,
  fee              bigint check (fee >= 0),
  idempotency_key  varchar(64) not null,
  trading_day      date not null,
  placed_tick      int  not null,
  filled_tick      int,
  created_at       timestamptz not null default now(),
  filled_at        timestamptz,
  unique (participant_id, idempotency_key),
  check ((order_type = 'LIMIT') = (limit_price is not null))
);
create index idx_orders_participant_created on orders (participant_id, created_at desc);
create index idx_orders_status_day on orders (status, trading_day);

create table cash_ledger (
  id              bigserial primary key,
  participant_id  bigint not null references participants (id),
  entry_type      varchar(20) not null check (entry_type in
                    ('INITIAL', 'BUY', 'SELL', 'FEE', 'BONUS')),
  -- 들어오면 +, 나가면 −. cash ↔ reserved_cash 사이 이동(묶기·풀기)은 총액이 그대로라 기록하지 않는다(I5)
  amount          bigint not null,
  order_id        bigint references orders (id),
  created_at      timestamptz not null default now()
);
create index idx_cash_ledger_participant on cash_ledger (participant_id, id);

create table attendance (
  participant_id  bigint not null references participants (id),
  attended_on     date   not null,
  primary key (participant_id, attended_on)
);
