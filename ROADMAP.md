# 모의투자 대회 서버 로드맵과 복습 노트

> 저장소: `mock-invest-league-api`
>
> 이 문서는 프로젝트를 만든 순서대로, **무엇을 했고 왜 그렇게 했는지**를 기록한다.
> 각 단계 끝의 "외울 것"은 면접에서 그대로 답할 수 있게 질문-답 형태로 정리했다.
> 새 작업을 할 때마다 같은 형식으로 단계를 추가한다.

## 진행 현황

| 단계 | 내용 | 상태 |
|---|---|---|
| 0 | 왜 이 프로젝트인가 | 완료 |
| 1 | 요구사항과 불변식 | 초안(기본값 확정 대기) |
| 2 | 가상 상장사 | 완료 |
| 3 | ERD와 DB 스키마 | 완료 |
| 4 | 프로젝트 뼈대(Gradle·설정·도커) | 완료 |
| 5 | 공통 코드(응답·예외·시간) | 완료 |
| 6 | 테스트 환경(Testcontainers·러너) | 완료 |
| 7 | Git 저장소 | 완료 |
| 8 | Company 엔티티 | 완료 |
| 9 | CompanyRepository와 테스트 | 완료 |
| 10 | 나머지 엔티티(User·Contest·ContestListing·Participant·Holding·Order·CashLedger) | 완료 |
| 11 | 테스트 구조(공용 컨테이너·단위 테스트·매핑 테스트) | 완료 |
| 12 | 시장가 주문 서비스(수수료·장 시간·참가·주문·내 계좌 API) | 완료 |
| 13 | 1주차 마무리(Swagger 확인, "왜" 노트) | 직접 할 일(남음) |
| 14 | 2주차 동시성 실험(갱신 손실 재현, 락 3종 비교, 선택) | 완료 |
| 15 | 3주차①: 틱 가격 엔진과 일일 배치 | 완료 |
| 16 | 3주차②: 지정가 주문(묶기·정산 배치·취소, I10) | 완료 |
| 17 | 3주차③: 가상 공시, 출석 보너스, 순위 스냅샷 | 완료 |
| 18 | 4주차①: 조회 성능(EXPLAIN, LATERAL, 인덱스 순서, 배치 INSERT) | 완료 |
| 19 | 4주차②: 조회 캐시(없음·서버 메모리·Redis 비교, 기본값 서버 메모리) | 완료 |
| 20 | 5주차①: 순위 스냅샷 정리, Docker 이미지, CI | 완료 |
| 21 | 5주차②: 배포 준비(EC2 + RDS, 운영 설정, 배포 스크립트), k6 부하 테스트 | 완료(서버 만들기는 직접) |
| 22 | 6주차①: README 다시 쓰기, 이력서 백엔드 v3, 백엔드 포트폴리오 1쪽 | 완료 |
| 23 | 6주차②: AWS에 실제로 올리기(시드니 EC2 + RDS), 원격 k6 러너 | 완료 |
| 24 | 6주차③: 시나리오 목록(정상 4 + 문제 11), 그래프(Prometheus + Grafana), 불변식 검사 API, k6 HTML 리포트 | 완료 |
| 25 | 6주차④: EC2 측정, 한 계좌 몰림 원인 추적(CPU steal), 사용자별 주문 속도 제한, 재측정, 측정 보고서 | 완료 |
| 26 | 다음 할 일(다음 시나리오 P3·P6 캐시 스탬피드, 이력서에 P1 사례) | 다음 |

6주 전체 일정:

| 주 | 할 일 | 끝났다는 기준 |
|---|---|---|
| 1 | 요구사항·불변식, 가상 상장사, ERD, 엔티티, 시장가 주문 + 테스트 | 매수·매도 API가 Swagger에서 동작, 불변식 테스트 통과 |
| 2 | 동시성 실험(락 3종 비교), 멱등성 | 비교표와 선택 이유 1쪽 |
| 3 | 지정가 주문, 가격 엔진, 폐장 배치 | 같은 시드 = 같은 결과, 배치 재실행 안전 |
| 4 | 조회 성능(EXPLAIN·인덱스), Redis 캐시 | 전후 수치 |
| 5 | Docker, CI/CD, 배포, k6 부하 테스트 | 배포 URL, k6 리포트 |
| 6 | README, 포트폴리오 문서, 이력서 반영 | 이력서 백엔드형 v3 |

---

# 0단계. 왜 이 프로젝트인가

## 한 일
- Spring 백엔드 포트폴리오로 **여러 모의투자 대회를 동시에 운영하는 서버**를 만들기로 정했다.
- 다른 후보(개발 뉴스·영상 수집 사이트)는 보류했다.

## 왜
1. **이전 프로젝트와 다른 점을 보여야 한다.** 이전에 만든 서비스들은 로직을 DB 함수나 BaaS(Supabase)에 두었다. 이번에는 **앱 서버(Spring)가 트랜잭션과 락을 책임지는 구조**를 직접 설계한다. 백엔드 면접은 거의 이 부분을 묻는다.
2. **돈이 걸린 도메인이라 정합성 문제가 자연스럽게 생긴다.** "같은 계좌에 주문 두 개가 동시에 오면?"이라는 질문 하나로 트랜잭션, 격리 수준, 락, 멱등성을 모두 다룰 수 있다.
3. **수집 사이트를 보류한 이유:**
   - 이미 Pitch-AC(데이터 수집 파이프라인)와 LLM Wiki(유튜브·인스타 수집)가 있어 같은 이야기가 세 번 반복된다.
   - 인스타그램·Reddit 콘텐츠를 공개 사이트에 쓰면 약관 문제가 생긴다.
   - 읽기 위주라 동시성·트랜잭션 이야기가 약하다.
4. **실사용 트래픽이 없다는 약점**은 "예상 트래픽 가정 + k6 부하 테스트"로 보완한다.

## 외울 것
- **Q. 왜 모의투자를 골랐나?** → 돈이 걸린 쓰기 요청이 몰리는 도메인이라, 동시성·트랜잭션·멱등성을 한 프로젝트 안에서 실험하고 수치로 비교할 수 있어서.
- **Q. 이전 프로젝트와 무엇이 다른가?** → 이전엔 DB 함수 한 개의 트랜잭션에 로직을 넣었고, 이번엔 앱 서버가 트랜잭션 경계와 락 전략을 정한다. 둘을 비교한 절을 README에 둔다.

---

# 1단계. 요구사항과 불변식

파일: `docs/01-요구사항-불변식.md`

## 한 일
- 기능을 F1~F12로 나눠 적었다(대회, 시세, 주문, 보너스·순위).
- **항상 참이어야 하는 규칙(불변식)** 11개를 I1~I11로 정했다.
- 1차 범위에서 뺄 것(부분 체결, 공매도, 호가창 매칭, 실시간 푸시)을 명시했다.
- 예상 트래픽을 가정했다: 대회 10개 × 참가자 1,000명, 피크 주문 30 TPS, 조회 90 TPS.

## 불변식 11개와 이유

| # | 규칙 | 왜 필요한가 |
|---|---|---|
| I1 | 현금·묶인 현금은 0 이상 | 음수 잔고 = 없는 돈으로 거래한 것 |
| I2 | 보유 수량은 0 이상, 가진 것보다 많이 못 판다 | 공매도는 범위 밖 |
| I3 | 금액은 원 단위 정수(`long`) | `double`은 0.1 + 0.2 ≠ 0.3 같은 오차가 생긴다 |
| I4 | 수수료 = 체결 금액 × 수수료율, 원 미만 버림 | 반올림 규칙이 여러 곳에 흩어지면 1원씩 어긋난다. 계산 함수는 한 곳에만 둔다 |
| I5 | `현금 + 묶인 현금 = 원장 합계`(초기 자금도 INITIAL 로 원장에 있음) | 돈이 생기거나 사라지지 않았음을 증명하는 식 |
| I6 | 같은 `Idempotency-Key` 주문은 한 번만 실행 | 네트워크 재시도로 같은 요청이 두 번 와도 두 번 사면 안 된다 |
| I7 | 출석 보너스는 하루 1번 | 중복 지급 방지 |
| I8 | 틱 가격은 전날 종가 ±제한폭 안 | 가격 엔진 버그로 말이 안 되는 가격이 나오는 것 방지 |
| I9 | 진행 중 대회, 장 시간 안에서만 주문 | 장 밖 주문은 체결 기준 가격이 없다 |
| I10 | 지정가 주문은 체결·취소·만료 중 정확히 하나로 끝난다. 배치를 두 번 돌려도 같다 | 배치 재실행 시 이중 체결 방지 |
| I11 | 미래 틱 가격·공시는 볼 수 없다 | 가격 경로를 미리 만들어 두므로, 조회 조건이 틀리면 미래가 새어 나간다 |

> **정정(10단계 때 발견):** 처음에는 I5를 "현금 = 초기 자금 + 원장 합계"로 적었다. 그런데 참가할 때 초기 자금을 원장에 `INITIAL`로 기록하므로, 그렇게 쓰면 초기 자금이 두 번 더해진다. "현금 + 묶인 현금 = 원장 합계"가 맞다. 규칙 문서와 코드가 어긋나면 테스트가 엉뚱한 값을 기대하게 되므로, 식은 한 군데(docs/01)에 정하고 나머지는 그 식을 따른다.

## 핵심 포인트: DB 제약만으로는 동시성 버그를 못 막는다

```
잔고 1,000,000원
요청 A: 잔고 읽음(1,000,000) → 600,000 빼서 400,000 저장
요청 B: 잔고 읽음(1,000,000) → 600,000 빼서 400,000 저장   ← A와 동시에 읽음
결과: 잔고 400,000원, 그런데 1,200,000원어치를 샀다
```
`CHECK (cash >= 0)`은 값이 음수인지만 본다. 400,000은 음수가 아니라서 통과한다. 이것을 **갱신 손실(lost update)**이라고 부르고, 2주차 실험의 출발점이다.

## 외울 것
- **Q. 불변식을 왜 문서로 먼저 정했나?** → 테스트 이름과 에러 코드가 이 번호를 따른다. "무엇을 지키는지"가 먼저 정해져야 락이나 트랜잭션 선택을 그 기준으로 평가할 수 있다.
- **Q. 금액을 왜 `long`으로?** → 원화는 소수 단위가 없고, `double`은 이진 소수 오차가 있다. `BigDecimal`도 되지만 원 단위 정수면 `long`이 더 단순하고 빠르다.
- **Q. 갱신 손실이란?** → 두 트랜잭션이 같은 값을 읽고 각자 계산해 덮어써서, 한쪽 변경이 사라지는 현상.
- **Q. CHECK 제약이 있는데 왜 락이 필요한가?** → CHECK는 결과값의 범위만 본다. 두 요청이 같은 값을 읽고 덮어쓰는 문제는 못 잡는다.

---

# 2단계. 가상 상장사

파일: `docs/02-가상-상장사.md`, `src/main/resources/db/migration/V2__seed_companies.sql`

## 한 일
- 6개 섹터(반도체·IT, 바이오, 에너지, 소비재, 금융, 엔터·게임) × 4개 = **24개 가상 회사**를 만들었다.
- 회사마다 종목 코드(4글자), 변동성 등급(LOW/MID/HIGH), 기준가를 정했다.
- 공시 이벤트 예시(회사·섹터·시장 단위)를 적었다.

## 왜
- **실존 기업을 쓰지 않는다.** 이전 프로젝트에서 남의 세계관(IP)을 썼다가 공개를 보류해야 했다. 모든 이름을 지어내고 "실존 기업과 무관"을 적었다.
- **섹터가 있어야 섹터 충격 공시를 만들 수 있다.** 예: "해상 풍력 보조금 축소" → 에너지 섹터 4개 동시 하락.
- **변동성 등급**은 3주차 가격 엔진에서 종목별 흔들림 크기를 다르게 주기 위함이다.

## 외울 것
- **Q. 시드 데이터를 왜 Flyway 마이그레이션으로 넣었나?** → 모든 환경(로컬, 테스트 컨테이너, 배포)에서 같은 순서로 같은 데이터가 들어간다. 테스트가 "24개가 있다"를 믿고 짤 수 있다.

---

# 3단계. ERD와 DB 스키마

파일: `docs/03-ERD.md`, `src/main/resources/db/migration/V1__init.sql`

## 테이블 11개

| 테이블 | 역할 |
|---|---|
| `users` | 사용자 |
| `companies` | 가상 상장사(모든 대회 공통) |
| `contests` | 대회 설정(기간, 장 시간, 초기 자금, 수수료, 제한폭, 시드) |
| `contest_listings` | 대회별 상장 종목과 그 대회의 기준가 |
| `price_ticks` | 대회·종목·거래일·틱 번호별 가격(미리 생성) |
| `disclosures` | 가상 공시 |
| `participants` | 한 사용자의 한 대회 계좌(현금, 묶인 현금) |
| `holdings` | 계좌별 보유 주식 |
| `orders` | 주문 |
| `cash_ledger` | 현금 이동 기록(원장) |
| `attendance` | 출석 기록 |

## 설계 결정과 이유

### 잔고를 `users`가 아니라 `participants`에 둔다
한 사용자가 대회 A와 대회 B에 동시에 참가하면, 두 대회의 돈은 완전히 별개다. 그래서 잔고의 주인은 "사용자"가 아니라 "대회 참가"다. `unique (contest_id, user_id)`로 한 대회에 계좌 하나만 허용한다.

### 금액·비율은 모두 정수
```sql
initial_cash      bigint  not null default 10000000,
fee_bp            int     not null default 50,     -- 50bp = 0.5%
price_limit_bp    int     not null default 3000,   -- 3000bp = 30%
```
비율도 `0.005` 같은 소수 대신 **bp(1bp = 0.01%)** 정수로 저장한다. 계산은 `금액 × bp / 10000`이다.

### `reserved_cash`, `reserved_quantity`(묶어 두기)
지정가 주문을 넣으면 체결될 때까지 돈(매수)이나 주식(매도)을 묶어 둔다. 묶지 않으면 잔고 100만 원으로 100만 원짜리 지정가 매수를 10개 넣을 수 있고, 나중에 동시에 체결되면 I1이 깨진다.

### `cash_ledger`(원장)
- 잔고는 **결과**, 원장은 **이유**다. 원장은 고치지 않고 추가만 한다.
- `amount`는 들어오면 +, 나가면 −. 종류는 `INITIAL, BUY, SELL, FEE, BONUS`.
- **묶기·풀기(`cash` ↔ `reserved_cash`)는 원장에 남기지 않는다.** 총액(`cash + reserved_cash`)이 그대로라, 남기면 I5 식이 깨진다.

### `price_ticks` 기본키 `(contest_id, company_id, trading_day, tick_no)`
"대회 3의 SBSI 오늘 틱 42번 가격"을 기본키 탐색 한 번으로 찾는다. 4주차 EXPLAIN 실험의 기준점이다.

### `orders`의 제약과 인덱스
```sql
unique (participant_id, idempotency_key),                        -- I6: 같은 키 두 번째 INSERT를 DB가 막음
check ((order_type = 'LIMIT') = (limit_price is not null)),      -- 지정가만 지정가 값을 가진다
create index idx_orders_participant_created on orders (participant_id, created_at desc); -- 내 주문 내역
create index idx_orders_status_day on orders (status, trading_day);                       -- 정산 배치가 대기 주문 찾기
```
- 테이블 이름이 `orders`인 이유: `order`는 SQL 예약어(`ORDER BY`)라 그대로 쓰면 따옴표가 필요하다.

### `attendance` 기본키 `(participant_id, attended_on)`
같은 날 두 번 INSERT하면 기본키 충돌로 실패한다. "하루 1번"(I7)을 DB가 보장한다.

### `version` 컬럼
`participants`, `holdings`에 있다. 2주차 낙관적 락(`@Version`) 실험용이다.

### CHECK 제약은 마지막 방어선
`cash >= 0`, `quantity >= 0` 등은 서비스 코드가 먼저 막고, 그래도 버그로 새어 나오면 DB가 거부한다. 다만 1단계에서 본 것처럼 갱신 손실은 못 막는다.

## 모듈(패키지) 경계
```
common   응답·예외·에러 코드·시간
user     사용자
contest  대회 생성·상태·참가
company  가상 상장사
price    가격 엔진·현재가·공시
account  계좌·보유·원장
order    주문 접수·체결·취소, 지정가 정산 배치
ranking  순위
```
규칙: 각 패키지는 Controller → Service → Repository 순서로만 의존한다. `order`가 잔고를 바꿀 때도 `account`의 서비스를 통하고, `account`의 리포지토리를 직접 부르지 않는다.

## 외울 것
- **Q. 원장을 왜 따로 두나?** → 잔고만 있으면 "왜 이 금액인지" 추적할 수 없다. 원장 합계로 잔고를 검증(I5)하고, 문제가 생기면 어느 주문에서 어긋났는지 찾을 수 있다.
- **Q. 멱등성을 DB에서 어떻게 보장하나?** → `(participant_id, idempotency_key)` 유니크 제약. 같은 키로 두 번째 INSERT가 오면 DB가 거부하고, 서비스는 첫 결과를 찾아 돌려준다.
- **Q. 지정가 주문에서 돈을 왜 묶나?** → 묶지 않으면 잔고보다 많은 지정가 주문을 넣을 수 있고, 동시에 체결되면 잔고가 음수가 된다.
- **Q. 비율을 왜 bp 정수로?** → 소수 오차를 없애고, 금액 계산을 전부 정수 연산으로 만든다.

---

# 4단계. 프로젝트 뼈대

## 4-1. `build.gradle`

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.7'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'jacoco'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
```
- **Spring Boot 3.5.7, Java 17:** 캡스톤(Ember 서버)과 같은 구성이다. 익숙한 버전으로 새 개념(락·트랜잭션)에만 집중한다. Java 17은 LTS(장기 지원)다.
- **toolchain:** 컴퓨터에 설치된 Java가 20이어도, Gradle이 17을 찾아(없으면 내려받아) 컴파일한다. 누가 어디서 빌드해도 같은 버전이 된다.
- **jacoco:** 테스트 커버리지 측정. 리포트는 `build/reports/jacoco/test/html/index.html`.

| 의존성 | 왜 |
|---|---|
| `starter-web` | REST API |
| `starter-data-jpa` | 엔티티·리포지토리(Hibernate) |
| `starter-validation` | 요청 값 검증(`@NotNull`, `@Positive`) |
| `starter-actuator` | `/actuator/health`, 지표(5주차 모니터링) |
| `springdoc-openapi` | Swagger 화면(`/swagger-ui.html`) |
| `flyway-core`, `flyway-database-postgresql` | DB 스키마를 SQL 파일 버전으로 관리 |
| `postgresql` | DB 드라이버 |
| `spring-boot-testcontainers`, `testcontainers:postgresql` | 테스트 때 진짜 PostgreSQL을 도커로 띄움 |

## 4-2. `application.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/league
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: Asia/Seoul
  flyway:
    enabled: true
logging:
  level:
    org.hibernate.SQL: debug
```

| 설정 | 왜 |
|---|---|
| 포트 `5433` | 다른 프로젝트의 PostgreSQL(기본 5432)과 겹치지 않게 |
| `open-in-view: false` | 켜져 있으면 컨트롤러·뷰까지 DB 연결을 붙잡고 지연 로딩이 된다. 끄면 트랜잭션 밖에서 지연 로딩 시 바로 오류가 나서, N+1이나 잘못된 트랜잭션 경계를 일찍 발견한다. 연결을 오래 잡지 않아 커넥션 풀에도 유리하다 |
| `ddl-auto: validate` | 테이블은 Flyway가 만들고, Hibernate는 엔티티와 테이블이 맞는지 **검사만** 한다. `update`는 운영 DB를 몰래 바꿀 수 있어 위험하다 |
| `time_zone: Asia/Seoul` | 장 시간·출석 날짜가 한국 시간 기준이라 |
| `org.hibernate.SQL: debug` | 실행되는 SQL을 로그로 본다. N+1 실험에 필요하다. 부하 테스트 때는 끈다 |

## 4-3. `docker-compose.yml`

```yaml
services:
  postgres:
    image: postgres:16-alpine
    ports:
      - "5433:5432"
    volumes:
      - league-pgdata:/var/lib/postgresql/data
```
- `docker compose up -d`로 로컬 개발용 DB를 띄운다.
- `volumes`: 컨테이너를 지웠다 다시 띄워도 데이터가 남는다.
- 비밀번호 `league`는 로컬 전용이다. 배포 환경은 환경 변수로 덮어쓴다.

## 4-4. Gradle wrapper
`gradlew`, `gradle/wrapper/`를 함께 커밋했다. 누구나 Gradle을 따로 설치하지 않고 `./gradlew`로 같은 버전(8.14.3)을 쓴다.

## 외울 것
- **Q. 왜 Flyway를 쓰나?** → 스키마 변경을 SQL 파일(V1, V2, …)로 버전 관리한다. 로컬·테스트·운영에 같은 순서로 적용되고, 어느 버전까지 적용됐는지 DB에 기록된다.
- **Q. `ddl-auto`를 왜 `validate`로?** → 스키마의 주인은 Flyway다. Hibernate가 테이블을 고치면 운영 DB가 예고 없이 바뀔 수 있다.
- **Q. OSIV(open-in-view)를 왜 끄나?** → 요청 전체 동안 DB 연결을 잡아서 트래픽이 몰리면 커넥션이 부족해진다. 끄면 트랜잭션 경계가 서비스 계층에 분명해진다.

---

# 5단계. 공통 코드

패키지: `com.jeffkm.mockleague.common`

## 5-1. `ApiResponse<T>`: 모든 응답의 같은 모양

```java
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

  public record ErrorBody(String code, String message) {}

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static ApiResponse<Void> fail(ErrorCode code, String message) {
    return new ApiResponse<>(false, null, new ErrorBody(code.name(), message));
  }
}
```
- **왜:** 클라이언트가 성공·실패를 항상 같은 방식(`success`, `error.code`)으로 처리할 수 있다.
- **`record`:** 필드·생성자·getter·`equals`가 자동으로 생기는 불변 객체. 응답 객체는 만든 뒤 바뀔 일이 없어서 적합하다.
- **`static` 팩토리(`ok`, `fail`):** `new ApiResponse<>(true, data, null)`보다 뜻이 분명하고, 실수로 `success=true`인데 `error`가 있는 응답을 만들 수 없다.

## 5-2. `ErrorCode`: 오류 종류를 한 곳에

```java
public enum ErrorCode {
  INSUFFICIENT_CASH(HttpStatus.CONFLICT, "현금이 부족합니다."), // I1
  INSUFFICIENT_QUANTITY(HttpStatus.CONFLICT, "보유 수량이 부족합니다."), // I2
  MARKET_CLOSED(HttpStatus.CONFLICT, "장이 열려 있지 않습니다."), // I9
  ...
}
```
- **왜 enum:** 오류 코드·HTTP 상태·기본 메시지를 한 줄에 묶는다. 오타가 컴파일 오류로 잡힌다.
- **왜 409(CONFLICT):** 요청 형식은 맞는데 현재 상태(잔고 부족, 장 마감)와 충돌한다는 뜻이다. 형식이 틀린 요청은 400이다.
- 주석으로 불변식 번호를 붙여, 어떤 규칙이 깨졌는지 바로 연결한다.

## 5-3. `BusinessException`

```java
public class BusinessException extends RuntimeException {
  private final ErrorCode errorCode;
  ...
}
```
- **왜 `RuntimeException`:** 체크 예외면 모든 메서드에 `throws`를 붙여야 한다. 또 Spring `@Transactional`은 기본으로 **런타임 예외에서만 롤백**한다. 업무 규칙 위반 시 롤백되어야 하므로 런타임 예외가 맞다.

## 5-4. `GlobalExceptionHandler`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
    ErrorCode code = e.errorCode();
    return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    log.error("처리되지 않은 예외", e);
    ...INTERNAL_ERROR...
  }
}
```
- **왜:** 컨트롤러마다 `try-catch`를 쓰지 않고, 예외를 한 곳에서 `ApiResponse`로 바꾼다.
- **예상 못 한 예외:** 로그에는 전체 내용을 남기고, 응답에는 "서버 오류"만 준다. 내부 정보(SQL, 스택)를 노출하지 않기 위해서다.

## 5-5. `TimeConfig`: `Clock` 빈

```java
@Bean
public Clock clock() {
  return Clock.system(KST);
}
```
- **왜:** 코드에서 `LocalDateTime.now()`를 직접 부르면 테스트에서 "지금 15시 29분"을 만들 수 없다. `Clock`을 주입받아 쓰면 테스트에서 `Clock.fixed(...)`로 원하는 시각을 고정할 수 있다.
- 장 시간 판단(I9), 출석 날짜(I7) 테스트에 필수다.

## 외울 것
- **Q. 공통 응답 형식의 장점은?** → 클라이언트 처리 일관성, 오류 코드로 분기 가능, 문서화가 쉬움.
- **Q. 업무 예외를 왜 런타임 예외로?** → `@Transactional` 기본 롤백 대상이 런타임 예외이고, `throws` 전파가 필요 없다.
- **Q. 현재 시각을 왜 `Clock`으로 받나?** → 시간에 의존하는 로직을 테스트에서 결정적으로 재현하려고.

---

# 6단계. 테스트 환경

## 6-1. Testcontainers로 진짜 PostgreSQL에서 테스트

```java
@SpringBootTest
@Testcontainers
class MockLeagueApplicationTests {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired
  JdbcTemplate jdbc;

  @Test
  void 앱이_뜨고_가상_상장사_24개가_들어간다() {
    Integer count = jdbc.queryForObject("select count(*) from companies", Integer.class);
    assertThat(count).isEqualTo(24);
  }
}
```
| 코드 | 뜻 |
|---|---|
| `@Testcontainers` + `@Container` | 테스트 시작 시 도커로 PostgreSQL을 띄우고, 끝나면 지운다 |
| `static` | 테스트 클래스 전체가 컨테이너 하나를 공유한다(테스트마다 새로 띄우면 느리다) |
| `@ServiceConnection` | 컨테이너의 주소·계정을 Spring `datasource` 설정에 자동으로 넣어 준다 |
| `@SpringBootTest` | 앱 전체를 띄운다. 이때 Flyway가 V1, V2를 적용한다 |

- **왜 메모리 DB(H2)가 아닌가:** H2는 PostgreSQL과 문법·락 동작이 다르다. 2주차 동시성 실험은 실제 DB의 락 동작을 봐야 의미가 있다.

## 6-2. 겪은 문제: Docker 29와 Testcontainers

- **증상:** `Could not find a valid Docker environment`, 내부적으로 `Status 400`.
- **원인:** Docker Engine 29는 API 1.40 미만 요청을 거부한다. Testcontainers 1.21.3이 쓰는 라이브러리(docker-java)는 기본으로 더 낮은 버전으로 요청한다.
- **해결:** `src/test/resources/docker-java.properties`에 한 줄 추가.
  ```properties
  api.version=1.44
  ```
- **배운 것:** 오류 메시지 맨 위(`Could not find...`)가 아니라 그 아래 **각 전략이 실패한 이유(Status 400)**까지 읽어야 원인이 보인다.

## 6-3. `run_tests.sh`: 안전한 테스트 러너

```bash
# 같은 테스트를 동시에 두 번 돌리지 않는다
if ! mkdir "$LOCK" 2>/dev/null; then ... exit 2; fi

# 실행 전부터 있던 Gradle 프로세스(다른 프로젝트)는 건드리지 않는다
BEFORE="$(pgrep -f 'GradleDaemon|GradleWorkerMain' | tr '\n' ' ')"
cleanup() { 이번 실행이 만든 프로세스만 kill; 잠금 해제; }
trap cleanup EXIT

./gradlew --no-daemon test "$@" &
pid=$!
( sleep "$TIMEOUT"; kill -9 "$pid" ) &   # 워치독
wd=$!
wait "$pid"
pkill -P "$wd"; kill "$wd"               # 워치독과 그 안의 sleep 정리
```
| 장치 | 왜 |
|---|---|
| `mkdir` 잠금 | `mkdir`은 이미 있으면 실패하는 원자적 연산이라 잠금으로 쓴다. 동시 실행은 포트·컨테이너 경합의 흔한 원인 |
| 워치독 | 테스트가 멈춰도 정해진 시간(기본 600초) 뒤 강제 종료 |
| `trap cleanup EXIT` | 성공·실패·중단 어느 경우든 끝날 때 정리 |
| `BEFORE` 목록 | IntelliJ가 띄운 Gradle처럼 원래 있던 프로세스는 건드리지 않음 |
| `pkill -P "$wd"` | 워치독 셸만 죽이면 그 안의 `sleep`이 고아로 남는다. 자식까지 정리 |

## 외울 것
- **Q. 왜 Testcontainers?** → 운영과 같은 DB에서 테스트해야 SQL 방언·제약·락 동작 차이로 생기는 버그를 잡는다.
- **Q. `@ServiceConnection`은 무엇을 하나?** → 컨테이너가 임의 포트로 뜨므로, 그 접속 정보를 Spring 설정에 자동으로 연결한다.

---

# 7단계. Git 저장소

- **새 비공개 저장소** `JeffKM/mock-invest-league-api`를 만들었다. 공개는 6주차에 README와 수치가 갖춰지면 한다.
- **기존 모의 주식 저장소를 재사용하지 않은 이유:**
  1. 기존 저장소의 커밋 기록에 공개를 보류한 세계관이 남아 있다. 이름을 바꿔도 기록은 남는다.
  2. TypeScript 프로젝트와 Java 프로젝트가 한 저장소에 섞이면 무엇이 이 프로젝트인지 흐려진다.
  3. "DB 함수 방식에서 앱 서버 방식으로 다시 설계했다"는 이야기는 첫 커밋부터 깨끗한 기록이 있어야 산다.
- 커밋 메시지는 한국어로, 기능 단위로 작게 쓴다.

---

# 8단계. Company 엔티티

파일: `company/Sector.java`, `company/Volatility.java`, `company/Company.java`

## 엔티티란
DB 테이블의 한 줄(회사 하나)을 Java 객체로 받기 위한 클래스다. 컬럼 하나가 필드 하나다.
```
DB:   companies (id, ticker, name, sector, volatility, base_price, description)
Java: Company   (id, ticker, name, sector, volatility, basePrice,  description)
```

## 코드

```java
public enum Sector { SEMI_IT, BIO, ENERGY, CONSUMER, FINANCE, ENTER }
public enum Volatility { LOW, MID, HIGH }
```

```java
@Entity
@Table(name = "companies")
public class Company {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 8)
  private String ticker;

  @Column(nullable = false, unique = true, length = 50)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Sector sector;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Volatility volatility;

  @Column(nullable = false)
  private long basePrice;

  @Column(length = 200)
  private String description;

  protected Company() {}

  public Long getId() { return id; }
  public String getTicker() { return ticker; }
  // ... 나머지 getter
}
```

## 한 줄씩 왜

| 코드 | 왜 이렇게 했나 | 다르게 하면 |
|---|---|---|
| `@Entity` | JPA가 관리하는 객체라고 표시 | 없으면 리포지토리가 이 클래스를 모른다 |
| `@Table(name = "companies")` | 실제 테이블 이름 지정 | 없으면 기본 규칙으로 `company` 테이블을 찾아 기동 실패 |
| `@Id` | 기본키 필드 표시 | 없으면 엔티티로 인정되지 않는다 |
| `GenerationType.IDENTITY` | DB의 `bigserial`(자동 증가)에 번호를 맡김 | `SEQUENCE`는 INSERT 전에 번호를 미리 받아 배치 INSERT가 가능하다. `IDENTITY`는 INSERT해야 번호를 알 수 있어 배치 INSERT가 안 된다. 회사는 시드로만 들어와서 상관없다 |
| `Long id`(대문자) | 저장 전에는 id가 없으므로 `null`이어야 한다 | `long`이면 0이 되어 "id 0번 회사"처럼 보인다 |
| `long basePrice`(소문자) | 가격은 항상 있는 값이라 `null`이 필요 없고, `bigint`와 맞는다 | `int`는 약 21억까지라 큰 금액에서 넘칠 수 있다(I3) |
| `EnumType.STRING` | enum 이름(`"SEMI_IT"`)으로 저장. 스키마도 `varchar` | 기본값 `ORDINAL`은 순서 번호(0,1,2)로 저장한다. 중간에 값을 끼워 넣으면 기존 데이터의 뜻이 몰래 바뀐다 |
| `@Column(nullable, unique, length)` | 스키마의 제약을 코드에서도 보이게 기록 | `validate`는 이것을 검사하지 않는다. 컬럼 존재와 타입만 본다. 실제로 지키는 건 DB다 |
| 필드 이름 `basePrice` | Spring의 기본 이름 규칙이 `base_price`로 바꿔 준다 | 직접 `@Column(name = "base_price")`를 써도 된다 |
| `protected Company() {}` | JPA가 조회 결과를 담을 빈 객체를 만들 때 쓴다 | `public`이면 다른 코드가 값이 빈 회사를 만들 수 있다. `private`이면 JPA가 프록시(지연 로딩용 가짜 객체)를 못 만든다 |
| setter 없음 | 회사 정보는 시드로만 들어오고 바뀌지 않는다 | setter가 있으면 어디서든 값을 바꿀 수 있어 추적이 어렵다 |
| `equals`/`hashCode` 없음 | JPA 엔티티의 `equals`는 id가 저장 전 `null`인 문제 등 함정이 많다 | 필요해질 때(컬렉션에 담을 때) 따로 다룬다 |

## 확인 방법
- `./run_tests.sh`가 통과하면 엔티티와 테이블이 맞은 것이다. `validate`가 기동할 때 비교한다.
- 연습: 필드 하나의 이름을 일부러 틀리게 바꿔 돌려 보고 오류 메시지(`Schema-validation: missing column ...`)를 읽어 본다.

## 외울 것
- **Q. 엔티티에 기본 생성자가 왜 필요하고, 왜 `protected`인가?** → JPA가 리플렉션으로 객체를 만들고 값을 채우기 때문에 필요하다. 지연 로딩 프록시가 상속해야 해서 `private`은 안 되고, 외부에서 빈 객체를 만들지 못하게 `public` 대신 `protected`로 둔다.
- **Q. `EnumType.ORDINAL`이 위험한 이유는?** → 순서 번호로 저장하므로 enum 순서가 바뀌면 기존 데이터가 다른 값으로 읽힌다.
- **Q. `IDENTITY`와 `SEQUENCE`의 차이는?** → `IDENTITY`는 INSERT 시 DB가 번호를 정해 INSERT 전에는 id를 모르고 배치 INSERT가 안 된다. `SEQUENCE`는 시퀀스에서 번호를 미리 받아 와 배치가 가능하다.
- **Q. id는 왜 래퍼 타입인가?** → 저장 전 "아직 없음"을 `null`로 표현하려고.
- **Q. `ddl-auto: validate`는 무엇을 검사하나?** → 테이블·컬럼 존재와 타입. 길이·null 허용·유일성은 검사하지 않는다.

---

# 9단계. CompanyRepository와 테스트

## 리포지토리

```java
public interface CompanyRepository extends JpaRepository<Company, Long> {

  Optional<Company> findByTicker(String ticker);

  List<Company> findBySectorOrderByTickerAsc(Sector sector);
}
```

| 코드 | 왜 |
|---|---|
| `JpaRepository<Company, Long>` | `<엔티티 타입, id 타입>`. 구현 없이 `save`, `findById`, `findAll`, `count` 등이 생긴다 |
| `interface`만 있고 구현 없음 | Spring Data가 실행 시점에 구현 객체를 만들어 준다 |
| `findByTicker` | **메서드 이름으로 쿼리를 만든다.** `findBy` + 필드 이름 → `where ticker = ?` |
| `Optional<Company>` | 없는 코드도 들어올 수 있다. `null` 대신 Optional로 "없을 수 있음"을 타입에 드러내 부르는 쪽이 처리하도록 강제한다 |
| `findBySectorOrderByTickerAsc` | `where sector = ? order by ticker asc`. 결과가 여러 개라 `List` |

## 테스트

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CompanyRepositoryTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired
  CompanyRepository companyRepository;

  @Test
  void 종목_코드로_회사를_찾는다() {
    Company company = companyRepository.findByTicker("SBSI").orElseThrow();
    assertThat(company.getName()).isEqualTo("솔빛실리콘");
    assertThat(company.getSector()).isEqualTo(Sector.SEMI_IT);
    assertThat(company.getBasePrice()).isEqualTo(84_000L);
  }

  @Test
  void 없는_종목_코드는_빈_값이다() {
    assertThat(companyRepository.findByTicker("NONE")).isEmpty();
  }

  @Test
  void 섹터별로_4개씩_코드_순으로_나온다() {
    List<Company> bio = companyRepository.findBySectorOrderByTickerAsc(Sector.BIO);
    assertThat(bio).extracting(Company::getTicker)
        .containsExactly("CRGN", "DAPH", "PRCB", "SBDX");
  }
}
```

| 코드 | 왜 |
|---|---|
| `@DataJpaTest` | 앱 전체가 아니라 **JPA 관련 부분만** 띄운다. `@SpringBootTest`보다 빠르다. 각 테스트는 끝나면 롤백된다 |
| `replace = NONE` | `@DataJpaTest`는 기본으로 DB를 메모리 DB로 바꾸려 한다. 이 프로젝트에는 메모리 DB가 없어 실패한다. "설정된 DB(컨테이너)를 그대로 써라"는 뜻 |
| 테스트 이름이 한국어 | 실패했을 때 무엇이 깨졌는지 리포트에서 바로 읽힌다 |
| `orElseThrow()` | 값이 없으면 테스트가 즉시 실패한다 |
| `84_000L` | 숫자 중간의 `_`는 읽기 쉽게 하는 구분자. `L`은 `long` 값 |
| `extracting(...).containsExactly(...)` | 목록에서 종목 코드만 뽑아 **순서까지** 정확히 비교한다. 정렬 조건(`OrderByTickerAsc`)이 실제로 동작하는지 확인 |
| 없는 경우 테스트 | 정상 경로만 테스트하면 `Optional` 처리 실수를 못 잡는다 |

## 결과
테스트 4개 통과(기동 1, 리포지토리 3).

## 외울 것
- **Q. Spring Data JPA 리포지토리는 구현을 누가 만드나?** → Spring이 실행 시점에 프록시 구현체를 만든다. 메서드 이름 규칙을 해석해 JPQL을 만든다.
- **Q. `@DataJpaTest`와 `@SpringBootTest`의 차이는?** → `@DataJpaTest`는 JPA 계층만 띄우고 테스트마다 롤백한다. `@SpringBootTest`는 앱 전체를 띄운다.
- **Q. 조회 결과에 왜 `Optional`을 쓰나?** → "없을 수 있음"을 타입으로 드러내 NPE를 줄인다.

---

# 10단계. 나머지 엔티티

## 한 일
| 패키지 | 엔티티 | 테이블 | 핵심 |
|---|---|---|---|
| `user` | `User` | `users` | 닉네임, 생성 시각 |
| `contest` | `Contest`, `ContestStatus` | `contests` | 대회 규칙, 상태 전이 |
| `contest` | `ContestListing`, `ContestListingId` | `contest_listings` | **복합키**, 1주차 임시 현재가 |
| `account` | `Participant` | `participants` | 잔고, 출금·입금 규칙 |
| `account` | `Holding`, `HoldingId` | `holdings` | **복합키**, 평균 매입가 |
| `account` | `CashLedger`, `LedgerEntryType` | `cash_ledger` | 부호 규칙, 추가만 |
| `order` | `Order`, `OrderSide`, `OrderType`, `OrderStatus` | `orders` | 상태 전이(한 번만) |

나중에 만드는 것: `PriceTick`·`Disclosure`(3주차 가격 엔진), `Attendance`(출석 보너스).

## 10-1. 연관관계: `@ManyToOne(fetch = LAZY)` 단방향만

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "contest_id")
private Contest contest;
```

| 코드 | 왜 | 다르게 하면 |
|---|---|---|
| `@ManyToOne` | 참가자 여러 명 → 대회 하나. FK(`contest_id`)를 가진 쪽에 둔다 | |
| `fetch = LAZY` | 참가자를 읽을 때 대회까지 바로 조인하지 않는다. `getContest().getName()`처럼 실제로 쓸 때 쿼리가 나간다 | `@ManyToOne`의 기본값은 **EAGER**다. 참가자 목록 100건을 읽으면 대회 조회가 따라붙어 N+1이 생기기 쉽다 |
| `optional = false` | 대회 없는 참가자는 없다. 스키마의 `not null`과 맞춘다 | |
| 단방향만 | `Contest`에 `List<Participant>`를 두지 않는다 | 양방향은 양쪽 값을 같이 맞춰야 하고, 실수로 대회 하나에 참가자 만 명을 통째로 불러오기 쉽다. 필요해질 때 추가한다 |

## 10-2. 복합키: `@EmbeddedId` + `@MapsId`

`holdings`의 기본키는 `(participant_id, company_id)`다. 한 계좌에 한 종목은 한 줄뿐이라 이 두 값이 자연스러운 키다.

```java
@Embeddable
public class HoldingId implements Serializable {
  @Column(name = "participant_id") private Long participantId;
  @Column(name = "company_id")     private Long companyId;
  // equals, hashCode 필수
}

@Entity
public class Holding {
  @EmbeddedId
  private HoldingId id;

  @MapsId("participantId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "participant_id")
  private Participant participant;
  ...
}
```

| 코드 | 왜 |
|---|---|
| `@Embeddable` 키 클래스 | 키 두 개를 하나의 값 객체로 묶는다 |
| `Serializable` | JPA 명세가 복합키 클래스에 요구한다 |
| `equals`/`hashCode` | 영속성 컨텍스트(1차 캐시)는 키로 엔티티를 찾는다. 값이 같은 키를 같은 키로 인식하려면 값 비교가 필요하다. 없으면 같은 행을 다른 엔티티로 취급한다 |
| `@MapsId("participantId")` | "키 안의 `participantId`는 이 연관관계(`participant`)의 id와 같다"는 뜻. 같은 컬럼을 키와 FK가 함께 쓴다 |
| `@IdClass`가 아닌 이유 | `@IdClass`는 엔티티에 키 필드를 따로 또 적어야 한다. `@EmbeddedId`는 키를 객체 하나로 다뤄 `findById(new HoldingId(...))`가 깔끔하다 |

- 리포지토리 타입: `JpaRepository<Holding, HoldingId>`. id 타입 자리에 키 클래스가 들어간다.
- 키 안의 필드로 찾기: `findByIdParticipantId(...)` → `id.participantId` 경로.
- 알아 둘 점: id를 직접 넣어 만든 엔티티는 `save()` 때 Spring Data가 "이미 있는 것"으로 보고 `merge`를 부른다. 그래서 INSERT 전에 SELECT가 한 번 더 나간다. 성능이 문제가 되면 `Persistable`로 새 엔티티임을 알려 줄 수 있다(4주차 쿼리 로그 확인 때 볼 것).

## 10-3. 엔티티가 스스로 규칙을 지킨다

### `Participant.withdraw`
```java
public void withdraw(long amount) {
  requirePositive(amount);
  if (cash < amount) {
    throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
  }
  cash -= amount;
}

public void deposit(long amount) {
  requirePositive(amount);
  cash = Math.addExact(cash, amount);
}
```
| 코드 | 왜 |
|---|---|
| 잔고 변경을 엔티티 메서드로 | `setCash()`를 열어 두면 서비스 어디서든 검증 없이 바꿀 수 있다. 바꾸는 길을 하나로 만들면 I1을 한 곳에서 지킨다 |
| 업무 위반은 `BusinessException` | 사용자 잘못(잔고 부족)이라 409로 응답한다 |
| 0 이하 금액은 `IllegalArgumentException` | 사용자가 아니라 **프로그램 버그**다. 500으로 드러나야 고친다 |
| `Math.addExact` | `long`이 넘치면 조용히 음수가 된다. `addExact`는 넘치는 순간 예외를 던진다 |

### `@Version`을 아직 붙이지 않은 이유
```java
/** 2주차 낙관적 락 실험용 컬럼. 지금은 일부러 @Version 을 붙이지 않는다. */
@Column(nullable = false)
private long version;
```
붙이는 순간 낙관적 락이 켜진다. 2주차에는 먼저 **락이 없을 때 갱신 손실이 실제로 생기는 것**을 테스트로 재현하고, 그다음 락 세 가지를 비교한다. 출발점이 있어야 비교 수치가 의미 있다.

### `Holding`의 평균 매입가
```java
public long decrease(long qty) {
  if (availableQuantity() < qty) throw new BusinessException(ErrorCode.INSUFFICIENT_QUANTITY);
  long removedCost = (qty == quantity)
      ? totalCost
      : Math.multiplyExact(totalCost, qty) / quantity;
  quantity -= qty;
  totalCost -= removedCost;
  return removedCost;
}
```
| 코드 | 왜 |
|---|---|
| 평균가가 아니라 `totalCost`(매입 총액)를 저장 | 평균가를 저장하면 살 때마다 나눗셈 오차가 쌓인다. 총액과 수량만 두고 평균은 필요할 때 계산한다 |
| 일부 매도는 비율대로 원가를 덜어 냄 | 20주(220,000원) 중 5주를 팔면 55,000원을 덜어 내 평균가 11,000원이 유지된다 |
| **전부 팔면 원가도 전부** | 3주를 100,000원에 사고 1주, 2주로 나눠 팔면 버림 오차로 1원이 남을 수 있다. 마지막에 전부를 덜어 내 0으로 맞춘다 |
| 곱셈 먼저, 나눗셈 나중 | `totalCost / quantity * qty`는 먼저 버림이 일어나 오차가 커진다 |
| `availableQuantity()` = 보유 − 묶인 수량 | 지정가 매도로 묶인 주식은 또 팔 수 없다(I2) |

### `CashLedger.record`: 부호는 종류가 정한다
```java
public static CashLedger record(Participant participant, LedgerEntryType type, long size, Order order) {
  if (size <= 0) throw new IllegalArgumentException(...);
  entry.amount = type.isInflow() ? size : -size;
  ...
}
```
부르는 쪽이 `-840000`처럼 부호를 직접 넣게 하면 실수로 `BUY`를 +로 남길 수 있다. 크기만 받고 부호는 `LedgerEntryType`(`INITIAL`·`SELL`·`BONUS`는 +, `BUY`·`FEE`는 −)이 붙인다. 실수할 수 없는 구조로 만드는 것이 검증 코드를 늘리는 것보다 낫다.

### `Order`: 상태는 PENDING에서 한 번만 바뀐다
```java
public void fill(long price, long fee, int tick, Instant at) {
  requirePending("체결");
  this.status = OrderStatus.FILLED;
  ...
}
private void requirePending(String action) {
  if (status != OrderStatus.PENDING) throw new IllegalStateException(...);
}
```
- 이미 체결된 주문을 또 체결하려는 건 버그다. 엔티티가 막는다(I10).
- **단, 이것만으로는 동시성은 못 막는다.** 배치 두 개가 같은 주문을 동시에 읽으면 둘 다 PENDING으로 보인다. 3주차에 `update orders set status='FILLED' where id=? and status='PENDING'`(조건부 UPDATE)로 DB 수준에서 한 번만 성공하게 만든다.
- 생성은 `Order.market(...)`, `Order.limit(...)` 정적 팩토리로만 한다. 시장가 주문에 지정가가 들어가는 조합을 만들 수 없다(스키마 CHECK와 같은 규칙).

### `Contest`: 기본값은 상수로
스키마에 `default 10000000`이 있어도, JPA는 INSERT할 때 모든 컬럼 값을 보낸다. 그래서 DB 기본값이 쓰이지 않는다. Java 쪽에 `DEFAULT_INITIAL_CASH` 같은 상수를 두고 `Contest.withDefaults(...)`에서 채운다. **스키마 기본값과 상수가 같아야 한다**는 점을 주석으로 남겼다.

### `@CreationTimestamp`
`createdAt`, `joinedAt`은 저장 시점에 Hibernate가 채운다. 생성자에서 `Instant.now()`를 부르면 테스트에서 시각을 통제하기 어렵다. 저장 시각처럼 업무 규칙과 관계없는 값은 Hibernate에 맡긴다. 반면 장 시간 판단처럼 규칙에 쓰는 시각은 `Clock`을 쓴다.

## 외울 것
- **Q. `@ManyToOne`의 기본 fetch는? 왜 LAZY로 바꾸나?** → 기본은 EAGER. 목록 조회 때 연관 엔티티 조회가 줄줄이 따라붙는 N+1을 막고, 필요한 곳에서만 fetch join으로 가져오기 위해서.
- **Q. 연관관계를 왜 단방향으로만?** → 양방향은 양쪽을 함께 맞춰야 하고 컬렉션을 통째로 불러오는 실수가 쉽다. 조회가 필요하면 리포지토리 쿼리로 해결한다.
- **Q. 복합키 클래스에 `equals`/`hashCode`가 왜 필요한가?** → 영속성 컨텍스트가 키로 엔티티를 찾기 때문. 값이 같은 키를 같은 키로 봐야 같은 행을 하나의 엔티티로 관리한다.
- **Q. `@MapsId`는 무엇인가?** → 복합키의 한 필드를 연관관계의 id로 채우라는 표시. 키와 FK가 같은 컬럼을 공유한다.
- **Q. 잔고를 왜 setter가 아니라 메서드로 바꾸나?** → 변경 경로를 하나로 만들어 규칙(I1)을 한 곳에서 지키려고. 객체가 자기 상태의 일관성을 책임진다.
- **Q. 업무 예외와 `IllegalArgumentException`을 어떻게 나누나?** → 사용자가 고칠 수 있는 상황(잔고 부족)은 업무 예외(409), 코드가 잘못 부른 경우(금액 0)는 프로그램 오류(500).
- **Q. 평균가를 왜 저장하지 않나?** → 나눗셈 오차가 누적된다. 총액과 수량을 저장하고 평균은 계산한다.
- **Q. 엔티티의 상태 검사만으로 이중 체결을 막을 수 있나?** → 한 트랜잭션 안에서는 막지만, 동시에 두 트랜잭션이 읽으면 못 막는다. 조건부 UPDATE나 락이 필요하다.
- **Q. 스키마 default가 있는데 Java 상수가 왜 필요한가?** → JPA INSERT는 모든 컬럼 값을 명시해 보내 DB 기본값이 적용되지 않는다.

---

# 11단계. 테스트 구조

## 11-1. 공용 컨테이너 설정

```java
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {
  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>("postgres:16-alpine");
  }
}
```
테스트 클래스에서 `@Import(PostgresTestConfig.class)`로 쓴다.

- **왜 바꿨나:** 전에는 테스트 클래스마다 `@Container static ...`을 두어서 클래스마다 컨테이너를 새로 띄웠다. 테스트 클래스가 늘수록 느려진다.
- **어떻게 빨라지나:** 컨테이너를 Spring 빈으로 두면, 설정이 같은 테스트끼리 **Spring 테스트 컨텍스트 캐시**를 공유해 컨테이너도 재사용한다.

## 11-2. 단위 테스트와 매핑 테스트를 나눈다

| 종류 | 파일 | DB | 무엇을 보나 | 속도 |
|---|---|---|---|---|
| 단위 | `ParticipantTest`, `HoldingTest`, `CashLedgerTest`, `OrderTest`, `ContestTest` | 없음 | 엔티티 메서드의 규칙(잔고 부족, 원가 계산, 부호, 상태 전이) | 밀리초 |
| 매핑 | `EntityMappingTest`, `CompanyRepositoryTest` | 실제 PostgreSQL | 저장 → 비우기 → 다시 읽기, 제약 조건, 쿼리 메서드 | 초 |
| 기동 | `MockLeagueApplicationTests` | 실제 PostgreSQL | 모든 엔티티가 스키마 검증을 통과해 앱이 뜨는지 | 초 |

규칙은 DB 없이 빠르게, DB와 맞물리는 부분만 컨테이너로 확인한다.

## 11-3. 매핑 테스트의 핵심: `flush` + `clear`

```java
private void flushAndClear() {
  em.flush();  // 지금까지 모은 변경을 SQL로 DB에 보낸다
  em.clear();  // 1차 캐시를 비운다
}
```
`clear` 없이 다시 조회하면 JPA는 DB에 가지 않고 **1차 캐시에 있던 같은 객체**를 돌려준다. 그러면 "DB에 제대로 저장됐는가"를 검증하지 못한다. 비우고 읽어야 진짜 DB 왕복이다.

## 11-4. 겪은 일 1: 중복 키 오류가 `flush`가 아니라 `save`에서 났다

```java
orderRepository.save(Order.market(participant, sbsi, OrderSide.BUY, 1, "dup", DAY, 0));

// IDENTITY 전략은 id 를 DB에서 받아야 하므로 save() 순간 INSERT 가 바로 나간다
assertThatThrownBy(() -> orderRepository.save(Order.market(..., "dup", ...)))
    .isInstanceOf(DataIntegrityViolationException.class);
```
- 보통 JPA는 변경을 모았다가 `flush` 때 SQL을 보낸다(쓰기 지연).
- 그런데 `IDENTITY`는 INSERT를 해야 id를 알 수 있어서, `save()` 순간 바로 INSERT한다. 쓰기 지연이 적용되지 않는다.
- 그래서 같은 키의 두 번째 주문은 `save()`에서 바로 실패한다. 리포지토리를 통해 나온 예외는 Spring이 `DataIntegrityViolationException`으로 바꿔 준다.

## 11-5. 겪은 일 2: 단위 테스트에서 `Company`가 필요했다

`Holding.empty(participant, company)`는 회사의 id를 읽는다. `null`을 넘기면 NullPointerException이 난다. `Company`는 시드로만 만들어져 공개 생성자가 없다. 그래서 단위 테스트에서는 **Mockito의 가짜 객체**를 넘겼다.
```java
return Holding.empty(p, mock(Company.class));
```
테스트를 위해 운영 코드에 공개 생성자를 추가하지 않고, 테스트 도구로 해결했다.

## 11-6. DB 제약도 테스트한다

```java
@Test
void I1_DB의_CHECK_제약은_서비스를_우회한_음수_잔고도_거부한다() {
  em.createNativeQuery("update participants set cash = -1 where id = :id") ... // 예외
}
```
엔티티 규칙을 우회해 SQL로 직접 음수를 넣어도 DB가 거부하는지 확인한다. "마지막 방어선"이 실제로 있는지 증명하는 테스트다.

## 결과
테스트 **34개** 통과(단위 23, 매핑·리포지토리 10, 기동 1).

## 외울 것
- **Q. 테스트에서 `flush`와 `clear`를 왜 하나?** → `flush`로 SQL을 실제로 보내고, `clear`로 1차 캐시를 비워야 다시 읽을 때 DB에서 가져온다. 안 하면 캐시의 같은 객체를 받아 저장 검증이 안 된다.
- **Q. 쓰기 지연이란? `IDENTITY`에서는 왜 안 되나?** → 변경을 모았다가 flush 때 한 번에 보내는 것. `IDENTITY`는 INSERT해야 id가 생기므로 persist 시점에 바로 INSERT한다.
- **Q. 단위 테스트와 통합 테스트를 왜 나누나?** → 규칙 검증은 DB 없이 빠르게 많이, DB 매핑·제약은 실제 DB로 적게. 실패했을 때 원인 범위도 좁아진다.
- **Q. Testcontainers 컨테이너를 어떻게 여러 테스트가 공유하나?** → 컨테이너를 `@ServiceConnection` 빈으로 두고 `@Import`하면, Spring 테스트 컨텍스트 캐시가 같은 설정의 테스트끼리 재사용한다.

---

# 12단계. 시장가 주문 서비스

## 한 일

1주차의 마지막 큰 덩어리다. 사용자가 대회에 참가해 시장가로 사고팔고 내 계좌를 보는 흐름 전체를 API로 만들었다.

| 만든 것 | 파일 | 지키는 불변식 |
|---|---|---|
| 수수료 함수 | `order/FeePolicy` | I4 |
| 장 시간 판단 | `Contest.openTickAt(now)` | I9 |
| 임시 현재가 | `market/PriceProvider`, `ListingBasePriceProvider` | — |
| 사용자 생성 | `POST /api/users` | — |
| 대회 생성·시작(관리자) | `POST /api/admin/contests`, `POST /api/admin/contests/{id}/start` | — |
| 참가 | `POST /api/contests/{id}/participants` | I5 출발점 |
| 시장가 주문 | `POST /api/contests/{id}/orders` | I1·I2·I4·I5·I6·I9 |
| 내 계좌 | `GET /api/contests/{id}/me` | F12 |

호출 흐름은 **컨트롤러 → 서비스(트랜잭션) → 엔티티(규칙) → 리포지토리** 순서다.

- 컨트롤러는 HTTP를 서비스 호출로 바꾸는 일만 한다.
- 서비스는 순서와 트랜잭션 경계를 책임진다.
- 엔티티는 규칙을 지킨다.

## 12-1. 수수료 함수(I4)

```java
public static long fee(long amount, int feeBp) {
  if (amount < 0 || feeBp < 0 || feeBp > BP_DENOMINATOR) {
    throw new IllegalArgumentException(...);
  }
  return Math.multiplyExact(amount, (long) feeBp) / BP_DENOMINATOR;
}
```

**한 줄씩 왜**
- **계산을 한 곳에만 둔다.** 매수 쪽은 반올림, 매도 쪽은 버림처럼 서로 다르게 계산하면 1원씩 어긋나고, 원장 합계(I5)가 깨진다.
- **`/ 10_000`(정수 나눗셈)이 곧 버림이다.** 금액과 비율이 모두 0 이상이라 Java 정수 나눗셈의 0 방향 버림이 내림과 같다. 음수를 허용했다면 −0.5가 0이 되어 내림이 아니게 된다. 입구에서 음수를 막는 이유다.
- **`multiplyExact`를 쓴다.** 그냥 `*`는 long 범위를 넘으면 조용히 음수가 된다. "수수료가 음수라 오히려 돈을 받는" 버그가 생긴다. 예외가 나는 편이 안전하다.
- **경계값을 테스트한다.** 199원은 0.995원이라 0원, 200원은 1원, 1,999원은 9원이다(반올림이었다면 10원). 반올림과 버림의 차이가 드러나는 값을 골랐다.

## 12-2. 장 시간 판단(I9): `Contest.openTickAt(now)`

```java
public int openTickAt(LocalDateTime now) {
  if (!isRunning()) throw new BusinessException(ErrorCode.CONTEST_NOT_RUNNING);
  boolean inPeriod = !day.isBefore(startsOn) && !day.isAfter(endsOn);
  boolean inHours  = !time.isBefore(openTime) && time.isBefore(closeTime);
  if (!inPeriod || !inHours) throw new BusinessException(ErrorCode.MARKET_CLOSED);
  return (int) (Duration.between(openTime, time).getSeconds() / tickSeconds);
}
```

**한 줄씩 왜**
- **장 시간은 반열린 구간 `[09:00, 15:30)`이다.** 15:30 정각은 이미 마감이다. 끝을 포함하면 "15:30:00에 들어온 주문"이 장중인지 애매해진다. 시간 구간은 보통 시작을 포함하고 끝을 제외한다.
- **엔티티는 시계를 모르고, 현재 시각을 인자로 받는다.** 엔티티 안에서 `LocalDateTime.now()`를 부르면 테스트가 실제 시각에 따라 통과했다 실패했다 한다. 인자로 받으면 `DAY.atTime(15, 30)`처럼 원하는 시각을 그대로 넣을 수 있다(`MarketHoursTest`).
- **틱 번호는 개장 후 지난 초를 틱 길이(300초)로 나눈 값이다.** 09:00~09:04:59가 0번, 15:29가 77번이라 하루에 78틱이다. 3주차 가격 엔진이 이 번호로 가격을 찾는다.
- **한 메서드가 확인과 틱 계산을 같이 한다.** 따로 두면 서비스에서 확인을 빼먹고 틱만 계산하는 실수가 가능하다. "장이 열려 있을 때만 틱 번호가 나온다"를 구조로 강제한 것이다.
- 1주차는 주말도 거래일로 본다. 3주차에 거래일 규칙을 정할 때 다시 본다.

## 12-3. 현재가: `PriceProvider` 인터페이스

```java
public interface PriceProvider {
  long currentPrice(Long contestId, Long companyId);
  Map<Long, Long> currentPrices(Long contestId);
}
```

**왜**
- **가격 엔진이 아직 없어서 1주차 구현은 상장 기준가를 그대로 돌려준다.** 3주차에 틱 가격 구현으로 바꿀 때 주문·계좌 서비스는 한 줄도 안 고친다. 서비스가 구현이 아니라 인터페이스에 의존하기 때문이다. 의존성 주입(DI)이 실제로 쓸모 있는 장면이다.
- **`currentPrices`(전체 한 번에)를 따로 둔다.** 내 계좌 화면에서 보유 종목마다 가격을 따로 물으면 종목 수만큼 쿼리가 나간다(N+1과 같은 모양). 대회 전체 가격을 쿼리 한 번에 가져와 Map으로 찾는다.

## 12-4. 주문 서비스: 하나의 트랜잭션, 여섯 단계

```java
@Transactional
public OrderResponse placeMarketOrder(contestId, userId, idempotencyKey, request) {
  // 0) 키 검사, 참가자 찾기(없으면 404 NOT_PARTICIPANT)
  // 1) 같은 키 주문이 있으면 → replay (다시 실행하지 않음)
  // 2) Instant at = clock.instant(); now = ...; tick = contest.openTickAt(now)
  // 3) price = priceProvider.currentPrice(...); amount = price × qty; fee = FeePolicy.fee(...)
  // 4) BUY : participant.withdraw(amount + fee); holding.increase(qty, amount)
  //    SELL: holding.decrease(qty); participant.deposit(amount − fee)
  // 5) Order.market(...) → order.fill(price, fee, tick, at) → save
  // 6) 원장 BUY/SELL(amount), FEE(fee) 기록
}
```

**한 줄씩 왜**
- **`@Transactional` 하나로 감싼다.** 4단계에서 돈이 빠진 뒤 6단계에서 실패하면 "돈은 빠졌는데 주식은 없는" 상태가 남는다. 하나의 트랜잭션이면 중간 어디서 예외가 나도 전부 롤백된다. 트랜잭션을 컨트롤러가 아니라 서비스에 두는 이유는 "하나의 업무 단위"를 아는 곳이 서비스이기 때문이다.
- **멱등 확인(1단계)을 장 시간 확인(2단계)보다 먼저 한다.** 15:29:59에 체결된 주문의 응답이 네트워크에서 사라져 클라이언트가 15:30:01에 다시 보낸다고 하자. 장 시간을 먼저 보면 "장 마감" 오류가 나서, 사용자는 체결됐는지 모르게 된다. 멱등을 먼저 보면 "체결됐다"는 처음 응답을 그대로 돌려준다(`I6_장이_닫힌_뒤_재전송해도_처음_결과를_돌려준다` 테스트).
- **같은 키에 다른 내용을 보내면 422 오류를 낸다.** 키가 같은데 수량이 다르면 클라이언트 버그다. 조용히 처음 결과를 주면 사용자는 11주를 샀다고 착각한다. Stripe 같은 결제 API도 같은 방식이다.
- **현재 시각은 한 번만 구한다(`Instant at`).** `now()`를 여러 번 부르면 15:29:59.999와 15:30:00.001 사이에 걸쳐 "틱은 77번인데 체결 시각은 마감 후"처럼 어긋날 수 있다.
- **매수는 `withdraw(amount + fee)` 한 번으로 뺀다.** 금액과 수수료를 따로 빼면 금액은 빠지고 수수료에서 잔고가 부족해 실패하는 중간 상태가 생긴다. 롤백되긴 하지만, 한 번에 검사하면 애초에 그런 상태가 없다. `addExact`는 합이 넘치는 경우를 막는다.
- **매도는 `decrease`를 먼저, `deposit`을 나중에 한다.** 수량이 부족하면 돈을 넣기 전에 실패한다. 순서를 바꿔도 롤백되지만, "검증이 실패할 수 있는 것을 먼저" 하는 습관이 안전하다.
- **보유가 없으면 `holdingRepository.save(Holding.empty(...))`의 반환값을 쓴다.** `Holding`은 id(복합키)를 직접 넣는 엔티티라 Spring Data가 새 엔티티인지 알 수 없다. 그래서 `save`가 `persist` 대신 `merge`를 부르고, merge는 **넘긴 객체가 아니라 관리되는 복사본을 돌려준다.** 넘긴 객체를 계속 고치면 그 변경은 저장되지 않는다. 그래서 반환값을 받아 `increase`한다.
- **주문을 먼저 저장하고 원장을 나중에 저장한다.** 원장의 `order_id`가 주문을 가리키는 외래키라 주문이 먼저 있어야 한다. `IDENTITY` 전략이라 `save` 순간 INSERT되어 id가 생긴다(11단계에서 본 내용).
- **원장은 거래 금액과 수수료를 두 줄로 남긴다.** 합쳐서 한 줄로 남기면 "이 대회에서 수수료로 얼마 냈나"를 나중에 계산할 수 없다. 수수료가 0원이면 남기지 않는다(`record`는 0원을 거부한다).
- **참가자와 보유는 `save`를 다시 부르지 않는다.** 트랜잭션 안에서 조회한 엔티티는 영속성 컨텍스트가 관리하므로, 커밋 때 바뀐 필드를 찾아 UPDATE를 만든다(더티 체킹).
- **일부러 락을 걸지 않았다.** 잔고를 읽고 → 계산하고 → 덮어쓰는 동안 같은 계좌 요청이 동시에 오면 갱신 손실이 생길 수 있다. 2주차에 이 문제를 먼저 재현하고, 그다음 락 방식들을 비교해 고친다. 서비스 클래스 주석에 이 사실을 적어 두었다.

## 12-5. 참가: 계좌와 INITIAL 원장을 한 트랜잭션에

```java
Participant participant = participantRepository.save(Participant.join(contest, user));
ledgerRepository.save(CashLedger.record(participant, LedgerEntryType.INITIAL, participant.getCash(), null));
```

- **둘이 한 트랜잭션이어야 한다.** 계좌만 생기고 원장이 빠지면 "현금 10,000,000 vs 원장 합계 0"으로 I5가 처음부터 깨진다.
- **이미 참가했는지 먼저 확인하고(친절한 409), 동시 요청은 DB 유니크 `(contest_id, user_id)`가 막는다.** 확인과 저장 사이에 다른 요청이 끼어들 수 있으므로 코드 확인만으로는 부족하다. 동시에 들어와 DB 제약에 걸리면 `DataIntegrityViolationException`이 나고, 예외 처리기가 409 `DATA_CONFLICT`로 바꾼다.

## 12-6. 내 계좌: 쿼리 수를 세는 습관

```java
@Query("select h from Holding h join fetch h.company where h.id.participantId = :pid and h.quantity > 0 ...")
```

- **`join fetch`를 쓴다.** 없으면 보유 종목마다 `company`를 지연 로딩하는 SELECT가 따로 나간다(N+1). 보유 10종목이면 11번이다. `join fetch`는 회사 정보까지 한 번에 가져온다.
- **`@Transactional(readOnly = true)`.** 읽기만 하는 메서드에 붙이면 Hibernate가 변경 추적(스냅샷 비교)을 생략해 조금 가볍다. "이 메서드는 아무것도 바꾸지 않는다"는 표시이기도 하다.
- **총자산은 현금 + 묶인 현금 + Σ(수량 × 현재가)이다.** F12의 순위 기준과 같은 식이다. 1주차는 가격이 움직이지 않으므로 총자산은 "초기 자금 − 낸 수수료"가 된다. 테스트가 이 사실로 계산을 검증한다.

## 12-7. DTO와 응답

- **엔티티를 그대로 응답하지 않고 record DTO로 바꾼다.** 엔티티를 그대로 내보내면 지연 로딩 필드가 직렬화되며 오류가 나거나 쿼리가 더 나간다. 또 내부 필드(`version` 등)가 API 계약이 되어 버린다.
- **주문 응답에는 "주문 후 잔고"를 넣지 않았다.** 같은 키로 다시 보냈을 때 처음과 같은 응답을 줘야 하는데(I6), 잔고는 그 사이 바뀔 수 있다. 응답에는 주문 시점에 확정된 사실만 담는다.
- **요청 검증(`@Valid`)**: 수량 1~1,000,000, 종목 코드 필수, 매수·매도 필수. 수량 상한은 `가격 × 수량`이 long을 넘지 않게 하는 입구 방어선이다.

## 12-8. 시간을 다루는 두 가지 장치

- **테스트용 `MutableClock`.** `Clock.fixed`는 한 번 정하면 못 바꾼다. 테스트 중간에 "장 마감 후"로 옮기려고 `set()`이 있는 시계를 만들고 `@Primary` 빈으로 등록했다. 운영 코드는 `Clock` 타입만 알기 때문에 바뀐 줄 모른다.
- **로컬용 `league.clock.fixed`.** 실제 시계를 쓰면 밤이나 대회 기간 밖에는 Swagger에서 주문이 전부 `MARKET_CLOSED`다. 이 설정을 주면 서버 시계가 그 시각에 멈춘다.

```bash
./gradlew bootRun --args='--league.clock.fixed=2026-12-01T10:00:00'
```

## 12-9. 겪은 일: Spring 표준 오류가 500이 되었다

실제 앱을 띄워 `curl`로 확인하다가 발견했다. 원래는 확인 스크립트의 Content-Type 헤더 실수였는데, 그 결과가 **500 INTERNAL_ERROR와 ERROR 로그**로 나왔다.

- **원인.** `@ExceptionHandler(Exception.class)`가 모든 예외를 500으로 바꿨다. 그 안에 Spring MVC가 던지는 예외도 들어 있었다. 없는 경로(404), 허용 안 된 메서드(405), 지원 안 하는 Content-Type(415)이 모두 클라이언트 잘못인데 서버 장애처럼 보였다.
- **해결.** 이 예외들은 `ErrorResponse` 인터페이스를 구현하고 자기 상태 코드를 들고 있다. `e instanceof ErrorResponse`이면 그 상태 코드를 그대로 쓴다. 테스트 `Spring_표준_오류는_500이_아니라_원래_상태_코드로_답한다`로 고정했다.
- **교훈.** "모든 예외를 잡는 처리기"는 편하지만, 클라이언트 오류까지 장애 알람을 울리게 만든다. 운영에서 500 비율로 알람을 걸면 가짜 알람이 쏟아진다.

## 결과

- 테스트 **59개** 통과
  - 단위 33: 수수료 5, 장 시간 5 추가
  - 매핑·리포지토리 10
  - 주문 API 통합 15
  - 기동 1
- 실제 앱 확인: 참가 → SBSI 3주 매수 → 내 계좌. 현금 9,746,740원(= 10,000,000 − 252,000 − 1,260), 총자산 9,998,740원.

## 외울 것

- **Q. 주문 처리를 왜 하나의 `@Transactional`로 묶었나?**
  → 잔고 차감, 보유 증가, 주문 저장, 원장 기록 중 하나라도 실패하면 전부 취소돼야 돈이 맞기 때문이다. 경계는 업무 단위를 아는 서비스에 둔다.
- **Q. 멱등성을 어떻게 보장했나?**
  → 계좌와 `Idempotency-Key`에 DB 유니크 제약을 걸었다. 서비스는 먼저 같은 키 주문을 찾아, 있으면 다시 실행하지 않고 처음 결과를 돌려준다. 키가 같은데 내용이 다르면 422다. 동시에 같은 키가 두 번 오는 경우는 유니크 제약이 막고, 응답을 다듬는 일은 2주차에 한다.
- **Q. 멱등 확인을 왜 장 시간 확인보다 먼저 하나?**
  → 마감 직전에 체결된 주문을 마감 후에 재전송하면, 장 시간을 먼저 볼 경우 "장 마감" 오류가 나서 체결 사실을 알 수 없다.
- **Q. 수수료는 왜 버림이고 어디서 계산하나?**
  → 규칙(I4)으로 정했고, 계산은 `FeePolicy` 한 곳에서만 한다. 여러 곳에서 계산하면 1원 오차가 원장 합계를 깨뜨린다. 곱셈은 `multiplyExact`로 넘침을 막는다.
- **Q. 현재 시각을 왜 `Clock` 빈으로 받나?**
  → 테스트에서 시각을 마음대로 정하려고. "15:30 정각은 마감" 같은 경계를 실제 시각과 무관하게 재현할 수 있다.
- **Q. `save()`의 반환값을 왜 받아 썼나?**
  → id를 직접 넣는 엔티티는 Spring Data가 새것인지 몰라 `merge`를 부르고, merge는 관리되는 복사본을 돌려준다. 넘긴 객체를 고치면 반영되지 않는다.
- **Q. 이 코드의 알려진 문제는?**
  → 락이 없어 같은 계좌로 동시 요청이 오면 갱신 손실이 날 수 있다. 일부러 남겼고, 2주차에 재현 → 비관적 락·낙관적 락·조건부 UPDATE 비교 → 선택의 순서로 고친다.
- **Q. N+1을 어디서 막았나?**
  → 내 계좌 조회에서 보유와 회사를 `join fetch`로 한 번에 가져오고, 가격도 대회 전체를 한 번에 가져와 Map으로 찾는다.

---

# 13단계. 1주차 마무리(직접 할 일)

- **Swagger에서 직접 눌러 보기.** 사용자 생성 → 대회 생성·시작 → 참가 → 매수·매도 → 내 계좌. `league.clock.fixed` 설정이 필요하다.
- **`docs/notes/week1-왜.md` 1쪽.** 주제는 복합키 방식, 트랜잭션 경계, 원장을 따로 둔 이유다. 이 문서의 8·10·12단계를 내 말로 줄이면 된다.
- 2주차 동시성 실험은 14단계에서 했다.

---

# 14단계. 2주차: 동시성 실험(갱신 손실 재현 → 락 3종 비교 → 선택)

## 한 일

1. 같은 계좌로 주문을 동시에 보내는 테스트를 만들어 **갱신 손실을 실제로 재현**했다.
2. 세 가지 방법으로 고쳤다. 설정 하나(`league.order.lock-strategy`)로 바꿔 가며 같은 시나리오를 돌린다.
3. 재시도를 맡는 `OrderFacade`를 추가했다. 낙관적 락 충돌과 같은 키 동시 INSERT를 새 트랜잭션으로 다시 시도한다.
4. 결과를 비교표로 정리하고 **기본값을 비관적 락으로 정했다**.

| 만든 것 | 파일 |
|---|---|
| 락 방식 선택 | `order/LockStrategy` (PESSIMISTIC · OPTIMISTIC · CONDITIONAL) |
| 비관적 락 조회, 조건부 UPDATE | `ParticipantRepository.findForUpdate`, `withdrawIfEnough`, `depositAtomically` |
| 보유 조건부 UPDATE | `HoldingRepository.insertEmptyIfAbsent`, `increaseAtomically`, `decreaseIfAvailable` |
| 낙관적 락 | `Participant.version`에 `@Version` |
| 재시도 | `order/OrderFacade` (컨트롤러는 이제 이것을 부른다) |
| 재시도 다 쓴 경우 409 | `GlobalExceptionHandler.handleOptimisticLock` |
| 시나리오 4개 × 방식 4개 | `ConcurrentOrderScenarios` + `*ConcurrencyTest` 4개 |

## 14-1. 먼저 재현했다: 100건 모두 "성공"인데 돈이 생겨났다

락을 넣기 전 코드(커밋 `6a0d860`)로 같은 계좌에 1주 매수 100건을 동시에 보냈다. SBSI 1주는 84,000원, 수수료는 420원이다.

| 시나리오 | 결과 |
|---|---|
| 매수 100건(보유 행 있음) | **100건 모두 성공 응답.** 그런데 현금은 15건만큼만 줄었다. 기대 1,473,580원, 실제 9,071,380원. **7,597,800원이 생겨났다.** 보유 수량도 101주가 아니라 16주 |
| 첫 매수 20건(보유 행 없음) | 11건 성공, 9건은 보유 행 PK 충돌(`DataIntegrityViolationException`). 성공한 11건 중에서도 현금이 759,780원 덜 빠졌다. 수량은 11주가 아니라 3주 |
| 같은 키 10건 | 1건 성공, 9건은 유니크 충돌로 오류. 돈은 맞았지만 같은 키 재요청이 "처음 결과"를 못 받았다 |

**왜 이렇게 되나.** 두 트랜잭션이 거의 동시에 실행되면 이렇게 된다.

```
A: SELECT cash → 10,000,000          B: SELECT cash → 10,000,000
A: 메모리에서 − 84,420 = 9,915,580     B: 메모리에서 − 84,420 = 9,915,580
A: UPDATE cash = 9,915,580, 커밋       B: UPDATE cash = 9,915,580, 커밋  ← A의 차감을 덮어씀
```

둘 다 성공했고, 주문과 원장도 2건씩 남았다. 그런데 현금은 한 번만 빠졌다. **원장 합계(I5)와 현금이 어긋난다.** DB의 `CHECK (cash >= 0)`은 값이 음수가 아니므로 이것을 못 막는다.

> 이 수치는 `@Version`을 붙이기 전에만 재현된다. 지금 코드는 `@Version`이 항상 켜져 있어서, 락 없이 돌려도 덮어쓰기 대신 버전 충돌로 실패한다. 다시 보고 싶으면 `git checkout 6a0d860` 후 같은 시나리오를 돌리면 된다.

## 14-2. 테스트 도구: 출발선(`CountDownLatch`)

```java
CountDownLatch ready = new CountDownLatch(Math.min(n, THREADS)); // 스레드가 출발선에 다 섰나
CountDownLatch start = new CountDownLatch(1);                    // 출발 신호
CountDownLatch done  = new CountDownLatch(n);                    // 다 끝났나

pool.submit(() -> {
  ready.countDown();
  start.await();          // 신호가 올 때까지 대기
  orderFacade.placeMarketOrder(...);
  ...
  done.countDown();
});
ready.await();  start.countDown();  done.await(120, SECONDS);
```

- **출발선이 왜 필요한가.** 스레드를 만들자마자 실행하면 먼저 만든 스레드가 먼저 끝나 버린다. 그러면 요청이 사실상 순서대로 처리돼 경합이 잘 생기지 않는다.
- **`done.await`에 시간 제한을 둔 이유.** 데드락이 나면 테스트가 영원히 멈춘다. 120초가 넘으면 실패로 처리한다.
- **무엇을 검사하나.** 성공 건수는 방식마다 다를 수 있다. 하지만 아래 세 가지는 성공이 몇 건이든 반드시 맞아야 한다.
  - 현금 = 처음 − 체결분 합계
  - 보유 수량 = 체결분 합계
  - 원장 합계 = 현금(I5)
- **서비스를 직접 부른다.** 보려는 것은 HTTP가 아니라 트랜잭션끼리의 경합이기 때문이다.

## 14-3. 방법 1: 비관적 락(`SELECT ... FOR UPDATE`)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Participant p where p.contest.id = :contestId and p.user.id = :userId")
Optional<Participant> findForUpdate(...);
```

- 계좌 행을 **읽는 순간 잠근다.** 같은 계좌를 잠그려는 다른 트랜잭션은 앞 트랜잭션이 커밋할 때까지 기다린다. 그래서 읽기 → 계산 → 쓰기가 한 줄로 선다.
- PostgreSQL에서는 Hibernate가 `FOR NO KEY UPDATE`를 만든다. `FOR UPDATE`보다 약간 약한 잠금이다. 다른 테이블이 이 계좌를 FK로 참조하는 INSERT(원장, 주문)는 막지 않는다.
- **잠그는 위치가 중요하다.** 멱등 확인보다 먼저 잠근다. 그러면 같은 키 요청 두 개가 동시에 와도 두 번째는 첫 번째의 커밋을 기다린다. 그다음 "이미 있음"을 보고 처음 결과를 돌려준다. 그래서 같은 키 10건 시나리오에서 **재시도가 0번**이다.
- 보유(Holding)는 따로 잠그지 않는다. 보유를 바꾸는 주문은 언제나 먼저 계좌를 잠그므로, 계좌 락 하나가 "이 계좌의 모든 변경"을 줄 세운다. 락을 하나만 잡으므로 순서가 엇갈려 생기는 데드락도 없다.

## 14-4. 방법 2: 낙관적 락(`@Version` + 재시도)

```java
@Version
@Column(nullable = false)
private long version;
```

- 잠그지 않는다. 대신 Hibernate가 UPDATE에 조건을 붙인다: `update participants set cash=?, version=version+1 where id=? and version=<읽었을 때 값>`.
- 그 사이 다른 트랜잭션이 먼저 바꿨으면 바뀐 행이 0개다. 그러면 `ObjectOptimisticLockingFailureException`이 난다. **덮어쓰기 대신 실패한다.**
- 실패한 요청은 `OrderFacade`가 다시 시도한다.

```java
for (int attempt = 1; ; attempt++) {
  try {
    return orderService.placeMarketOrder(...);           // 매번 새 트랜잭션
  } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
    if (attempt >= maxAttempts) throw e;                 // → 409 DATA_CONFLICT
    meterRegistry.counter("orders.retry", "reason", reason).increment();
    backoff(attempt);                                    // 5~20ms × 시도 횟수, 무작위
  }
}
```

- **재시도를 왜 서비스 "밖"에서 하나.** 실패한 트랜잭션은 이미 롤백 표시가 됐다. 영속성 컨텍스트에도 옛 값이 남아 있다. 같은 트랜잭션 안에서 다시 해 봐야 같은 옛 값으로 또 실패한다. 그래서 `@Transactional` 메서드를 바깥에서 다시 불러 "새로 읽기"부터 한다.
- **기다리는 시간을 왜 무작위로 하나(지터).** 같이 실패한 요청들이 똑같이 10ms 뒤에 다시 출발하면 또 부딪힌다. 출발 시점을 흩어 놓는다.
- **`DataIntegrityViolationException`도 다시 시도하는 이유.** 같은 키 주문이나 첫 보유 행을 다른 요청이 먼저 INSERT한 경우다. 다시 하면 "이미 있음"을 보고 처음 결과를 돌려준다(멱등). 보유 행이면 있는 행에 더한다.
- **보유에 `@Version`을 안 붙인 이유.** 보유가 바뀌는 주문은 언제나 같은 트랜잭션에서 계좌 현금도 바꾼다. 그래서 계좌 버전 하나로 충분하다. 예외가 하나 있다. 수수료율이 100%(`fee_bp = 10000`)면 매도 실수령액이 0이라 현금이 안 바뀐다. 실제로는 쓰지 않는 설정이다.
- **결과.** 재시도 5회로는 매수 100건 중 **43건만 체결**됐다. 57건은 재시도를 다 쓰고 409가 됐다. 돈은 정확했다. 재시도를 50회로 늘리면 100건 모두 체결됐지만, 재시도가 737번 일어났고 시간도 가장 오래 걸렸다(8.9초).

## 14-5. 방법 3: 조건부 UPDATE

```java
@Modifying
@Query("update Participant p set p.cash = p.cash - :amount where p.id = :id and p.cash >= :amount")
int withdrawIfEnough(Long id, long amount);   // 0이면 잔고 부족
```

- "읽고 → 계산하고 → 덮어쓰기"를 하지 않는다. **DB가 한 문장으로 계산하고 검사한다.** 문장 하나는 원자적이므로 사이에 끼어들 틈이 없다.
- 보유도 같은 방식이다.
  - 첫 매수: `insert ... on conflict do nothing`으로 빈 행을 만든다. 동시에 INSERT해도 PK 충돌 오류가 나지 않는다.
  - 매수: `quantity = quantity + ?`
  - 매도: `... where quantity - reserved_quantity >= ?`
- **주의 1: 메모리의 엔티티가 옛값이 된다.** `participant.getCash()`는 DB와 달라진다. 이 방식에서는 엔티티의 현금을 읽지도 고치지도 않는다. 고치면 커밋 때 더티 체킹이 옛값 기준으로 덮어쓴다.
- **주의 2: 규칙이 두 곳에 생긴다.** 매도 시 매입 원가를 비율대로 덜어 내는 규칙이 `Holding.decrease`(Java)와 SQL에 각각 있다. 하나만 고치면 둘이 어긋난다.
- **주의 3: 잠금이 없는 것이 아니다.** UPDATE는 그 행을 커밋까지 잠근다. 잠그는 시점이 늦을(계산이 끝난 뒤) 뿐이다. 같은 키 10건에서 재시도가 9번 난 이유도 이것이다. 두 번째 요청이 멱등 확인을 이미 통과한 뒤 UPDATE에서 기다리고, 주문 INSERT에서 유니크 충돌이 난다.

## 14-6. 비교표

같은 노트북, Testcontainers PostgreSQL 16, 스레드 32개, 커넥션 풀 10개(기본값)에서 한 번씩 돌린 값이다. 시간은 JIT 예열과 컨테이너 상태에 따라 흔들리므로 **크기 차이만** 본다.

| 시나리오 | 락 없음(재현) | 비관적 락 | 낙관적 락(재시도 5) | 낙관적 락(재시도 50) | 조건부 UPDATE |
|---|---|---|---|---|---|
| 매수 100건 | 100 성공, **현금 7,597,800원 오차** | 100 성공, 재시도 0, 4.2초 | **43 성공**, 재시도 313, 4.6초 | 100 성공, 재시도 737, 8.9초 | 100 성공, 재시도 0, 3.2초 |
| 매도 100건(보유 50주) | — | 50 성공, 50 수량 부족 | 44 성공, 56 충돌 실패 | 50 성공, 재시도 417 | 50 성공, 50 수량 부족 |
| 첫 매수 20건 | 11 성공, 9 오류, 오차 | 20 성공 | 9 성공, 재시도 60 | 20 성공, 재시도 91 | 20 성공 |
| 같은 키 10건 | 1 성공, 9 오류 | 10 모두 같은 주문, 재시도 0 | 10 모두 같은 주문, 재시도 9 | 같음 | 10 모두 같은 주문, 재시도 9 |
| 돈이 맞는가 | **아니오** | 예 | 예 | 예 | 예 |

| 기준 | 비관적 락 | 낙관적 락 | 조건부 UPDATE |
|---|---|---|---|
| 충돌이 잦을 때 | 기다렸다 처리. 실패 없음 | 재시도 폭증, 실패 발생 | 기다렸다 처리. 실패 없음 |
| 충돌이 드물 때 | 잠금 비용이 조금 듦 | 가장 가벼움 | 가벼움 |
| 도메인 규칙 위치 | 엔티티 한 곳 | 엔티티 한 곳 | **엔티티 + SQL 두 곳** |
| 같은 키 동시 요청 | 락 순서로 자연 해결 | 재시도로 해결 | 재시도로 해결 |
| 위험 | 오래 잡으면 커넥션·스레드가 묶임 | 재시도 설계 필요 | 엔티티가 옛값이 됨 |

## 14-7. 선택: 기본값은 비관적 락

`league.order.lock-strategy`의 기본값을 `PESSIMISTIC`으로 했다. 나머지 둘도 설정으로 켤 수 있게 남겨 비교 테스트가 계속 돈다.

1. **충돌 대상이 "한 사람의 계좌"다.** 다른 사용자끼리는 서로 막지 않는다. 한 계좌에 동시 요청이 몰리는 경우는 연타, 재전송, 여러 기기 정도다. 기다리는 비용이 작다.
2. **돈은 실패보다 기다림이 낫다.** 낙관적 락은 충돌이 나면 사용자에게 "다시 시도하세요"를 돌려줄 수 있다. 실험에서 재시도 5회로 57%가 실패했다.
3. **규칙을 엔티티 한 곳에 둔다.** 조건부 UPDATE가 가장 빨랐다. 하지만 매도 원가 계산이 SQL에도 생긴다. 3주차에 지정가(묶인 현금·수량)가 들어오면 SQL이 더 늘어난다.
4. **같은 키 동시 요청이 재시도 없이 해결된다.** 멱등 확인 전에 잠그기 때문이다.

**바꿔야 할 때.** 랭킹 집계처럼 "많은 사람이 같은 행 하나"를 갱신하는 곳이라면 조건부 UPDATE(또는 Redis 원자 연산)를 고른다. 충돌이 거의 없는 곳(프로필 수정 등)이라면 낙관적 락을 고른다.

## 결과

- 동시성 테스트 16개(시나리오 4 × 방식 4)를 포함해 전체 테스트가 통과했다.
- 현금, 보유 수량, 원장 합계(I5)가 모든 방식, 모든 시나리오에서 맞았다.
- 재시도 횟수는 Micrometer 카운터 `orders.retry`, `orders.retry.exhausted`로 남는다. 앱을 띄우면 `/actuator/metrics/orders.retry`로 볼 수 있다.

## 외울 것

- **Q. 갱신 손실을 어떻게 재현했나?**
  → 같은 계좌로 1주 매수 100건을 `CountDownLatch` 출발선에 세워 32개 스레드로 동시에 보냈다. 100건 모두 성공 응답이었다. 그런데 현금은 15건만큼만 줄어 7,597,800원이 생겨났고, 원장 합계와 현금이 어긋났다.
- **Q. `CHECK (cash >= 0)`이 있는데 왜 못 막았나?**
  → 덮어쓴 값이 음수가 아니기 때문이다. 제약은 "값이 말이 되나"만 본다. "다른 트랜잭션의 변경을 지웠나"는 모른다.
- **Q. 비관적 락과 낙관적 락의 차이는?**
  → 비관적 락은 읽을 때 잠가 다른 쪽을 기다리게 한다. 낙관적 락은 잠그지 않고, 쓸 때 version이 바뀌었으면 실패시킨다. 충돌이 잦으면 비관적 락이, 드물면 낙관적 락이 낫다.
- **Q. 낙관적 락 재시도를 왜 트랜잭션 밖에서 하나?**
  → 실패한 트랜잭션은 롤백 표시가 됐고 영속성 컨텍스트에 옛 값이 남아 있다. 새 트랜잭션에서 다시 읽어야 성공할 수 있다.
- **Q. 조건부 UPDATE가 제일 빨랐는데 왜 안 골랐나?**
  → 매도 원가 계산 같은 규칙이 SQL에도 생겨 두 곳을 같이 고쳐야 한다. 메모리의 엔티티가 옛값이 되는 함정도 있다. 한 계좌의 경합은 작아서 속도 차이가 결정적이지 않았다.
- **Q. 같은 Idempotency-Key 요청이 동시에 두 번 오면?**
  → 비관적 락에서는 계좌를 먼저 잠그고 멱등 확인을 하므로, 두 번째는 첫 번째 커밋 후 "이미 있음"을 보고 같은 결과를 받는다. 다른 방식에서는 유니크 제약이 두 번째 INSERT를 막는다. 그다음 `OrderFacade`가 다시 시도하면 처음 결과를 돌려준다. 어느 쪽이든 주문은 1건이다.
- **Q. 비관적 락의 위험은?**
  → 잠근 채 오래 걸리는 일(외부 API 호출 등)을 하면 기다리는 요청이 커넥션 풀을 다 차지한다. 그래서 트랜잭션 안에서는 DB 작업만 하고 짧게 끝낸다. 여러 행을 잠그면 순서를 고정해야 데드락이 안 난다. 여기서는 계좌 한 행만 잠근다.
- **Q. 보유(Holding)는 왜 따로 잠그지 않나?**
  → 보유를 바꾸는 주문은 언제나 계좌를 먼저 잠그거나 계좌 버전을 올린다. 계좌 하나가 그 계좌의 모든 변경을 줄 세운다.

---

# 15단계. 3주차①: 틱 가격 엔진과 일일 배치

## 한 일

지금까지 현재가는 상장 기준가 그대로였다. 이제 **미리 만든 가격 경로**로 바꿨다. 장이 열리면 5분마다 가격이 바뀌고, 주문은 그 틱의 가격으로 체결된다. 지정가 주문(다음 단계)은 이 가격 경로가 있어야 "언제 닿았나"를 판단할 수 있으므로 먼저 만들었다.

| 만든 것 | 파일 | 지키는 것 |
|---|---|---|
| 변동성 수치 확정 | `company/Volatility` (낮음 1.0%, 보통 2.0%, 높음 3.5%) | — |
| 가격 엔진(순수 계산) | `market/PriceEngine` | F4, I8 |
| 가격 저장·조회(JDBC) | `market/PriceTickRepository` | I11 |
| 날짜별 가격 생성 | `market/PriceGenerationService.ensureGeneratedThrough` | F4 |
| 틱 가격 제공 | `market/TickPriceProvider` (`ListingBasePriceProvider` 삭제) | F5 |
| "지금 보여 줄 틱" 계산 | `Contest.latestVisibleTick`, `ticksPerDay`, `contest/TickPosition` | F5, I11 |
| 일일 배치 | `contest/ContestLifecycleService`(본체), `ContestScheduler`(08:50·15:40) | F2 |
| 가격 준비 안 됨 | `ErrorCode.PRICE_NOT_READY`(503) | — |

`PriceProvider` 인터페이스 덕분에 주문 서비스는 한 줄만 바뀌었다. `currentPrice(...)`가 `priceAt(..., 날짜, 틱)`으로 바뀐 것뿐이다.

## 15-1. 가격 엔진: 기하 브라운 운동

```java
double sigma = volatility.dailySigma() / Math.sqrt(ticks);   // 틱 하나의 σ
double drift = -sigma * sigma / 2;
prices[0] = previousClose;                                     // 시가 = 전일 종가
for (int i = 1; i < ticks; i++) {
  x = x * Math.exp(drift + sigma * random.nextGaussian());     // 직전 가격 × e^(무작위 수익률)
  long clamped = Math.min(upper, Math.max(lower, Math.round(x)));  // 제한폭(I8)
  ...
}
```

- **왜 더하기가 아니라 곱하기인가.** "±1,000원"처럼 더하면 9,400원짜리 종목은 금방 0 아래로 간다. "직전 가격 × e^r"로 곱하면 가격은 절대 0이 되지 않는다. 비싼 종목과 싼 종목이 같은 비율로 흔들린다. 실제 주가 모델(블랙-숄즈)의 기본 가정과 같다.
- **틱 하나의 σ가 왜 "하루 σ ÷ √78"인가.** 독립된 무작위 움직임을 더하면 분산이 시간에 비례해 커진다. 78번 움직여 하루 분산이 σ²가 되려면 한 번의 분산이 σ²/78이어야 한다. 표준편차로는 σ/√78이다.
- **`−σ²/2`는 왜 빼나.** e^(σZ)의 평균은 1이 아니라 e^(σ²/2)로, 1보다 조금 크다. 그대로 두면 가격이 평균적으로 조금씩 오른다. 빼 주면 "평균적으로 제자리"인 경로가 된다.
- **제한폭 경계를 정수로.** 위쪽은 버림, 아래쪽은 올림으로 계산한다. 경계값 자체도 ±30%를 넘지 않는다. 최솟값은 1원이다.
- **벽에 닿으면.** 벽 밖의 값은 버리고 벽 가격에서 다시 출발한다.

## 15-2. 같은 시드 → 같은 경로

```java
Random random = new Random(seedFor(contestSeed, ticker, day));
```

- 시드를 대회 시드 + 티커 + 날짜로 만든다. 그래서 종목마다, 날마다 다른 난수열이 나온다. 같은 입력이면 언제 어디서 돌려도 같은 경로다.
- **회사 id 대신 티커를 쓰는 이유.** id는 DB가 정하는 값이라 DB를 새로 만들면 바뀔 수 있다. 티커는 바뀌지 않는다.
- **`java.util.Random`을 쓰는 이유.** 알고리즘이 Java 명세에 고정돼 있어 JDK 버전이 바뀌어도 같은 수가 나온다. 새 난수 API는 구현이 바뀔 여지가 있다.
- **splitmix64로 섞는 이유.** 12월 1일과 2일처럼 하루 차이 나는 입력이 비슷한 시드가 되지 않도록 비트를 흩는다.
- **결정적이라서 얻는 것.**
  - 버그 재현: 같은 시드로 같은 장을 다시 만들 수 있다.
  - 재실행 안전: 다시 만들어도 같은 값이다.
  - 서버 여러 대: 누가 먼저 넣든 내용이 같다.

## 15-3. 저장: JPA 대신 JdbcTemplate 배치

```java
jdbc.batchUpdate("""
    insert into price_ticks (contest_id, company_id, trading_day, tick_no, price)
    values (?, ?, ?, ?, ?)
    on conflict do nothing
    """, rows);   // 하루 24종목 × 78틱 = 1,872행
```

- **엔티티로 넣지 않은 이유.** JPA로 넣으면 객체 1,872개가 영속성 컨텍스트에 올라가 트랜잭션 내내 더티 체킹 대상이 된다. 가격 행은 한 번 쓰면 바뀌지 않으므로 그런 관리가 필요 없다.
- **JDBC 배치.** 문장 하나를 값만 바꿔 묶어서 보낸다. DB 왕복 횟수가 크게 준다.
- **`on conflict do nothing`.** 배치를 두 번 돌리거나 서버 두 대가 동시에 돌려도 오류 없이 한 벌만 남는다. 경로가 결정적이므로 누가 넣었든 값이 같다.

## 15-4. 생성 규칙: 하루씩 차례로, 몇 번을 불러도 안전하게

`ensureGeneratedThrough(contestId, 날짜)`는 그 날짜(종료일을 넘지 않게)까지 **아직 없는 날만** 만든다.

1. 만든 적이 없으면 첫날부터 시작한다. 전일 종가는 상장 기준가다.
2. 있으면 마지막으로 만든 날 다음 날부터, 그날 종가에서 출발한다.
3. 하루를 만들면 그날 종가가 다음 날의 출발점이 된다.

- **날짜를 건너뛸 수 없는 이유.** 다음 날 경로가 전날 종가에서 출발하기 때문이다.
- **얻는 것.** 서버가 사흘 꺼져 있었어도 다음 실행 때 빠진 날을 차례로 채운다.

## 15-5. 현재가와 I11: 미래 가격은 보이지 않는다

`Contest.latestVisibleTick(now)`가 "지금 보여 줄 가격이 있는 가장 최근 지점"을 정한다.

| 지금 | 지점 |
|---|---|
| 장 중 10:00 | 오늘 12번 틱 |
| 장 시작 전 08:00 | 전날 77번 틱(종가) |
| 장 마감 후 | 오늘 77번 틱 |
| 첫날 장 전·대회 시작 전 | 없음 → 상장 기준가 |
| 대회 종료 후 | 마지막 날 종가 |

```sql
select distinct on (company_id) company_id, price
  from price_ticks
 where contest_id = ? and (trading_day, tick_no) <= (?, ?)
 order by company_id, trading_day desc, tick_no desc
```

- 일일 배치가 **내일 가격까지 미리** 만들어 두므로, DB에는 미래 가격이 이미 있다. 그래서 조회 조건이 곧 I11이다.
- `(trading_day, tick_no) <= (?, ?)`는 행 값 비교다. 날짜가 앞서거나, 같은 날이면 틱이 같거나 앞선 행만 남는다.
- `distinct on`은 PostgreSQL 문법이다. 정렬된 결과에서 종목마다 첫 행(가장 최근 가격)만 남긴다.
- **4주차 재료로 남긴 것.** 대회가 길어지면 이 쿼리가 읽는 행이 늘어난다. EXPLAIN으로 확인하고 인덱스나 캐시로 줄일 예정이다.

**주문은 다른 방법을 쓴다.** 주문 서비스가 정한 (날짜, 틱)을 그대로 넘겨 `priceAt`으로 정확히 그 틱의 가격을 읽는다. 가격 쪽이 시계를 따로 읽으면 10:04:59.999와 10:05:00 사이에서 "서비스가 본 틱"과 "가격의 틱"이 어긋날 수 있다.

## 15-6. 일일 배치: 본체와 시계를 나눴다

- **`ContestLifecycleService.runDaily(today)` (본체).** 날짜만 받으므로 테스트에서 "12월 2일"을 넣어 바로 돌려 본다. 하는 일은 세 가지다.
  - 시작일이 된 예정 대회를 시작한다(가격도 함께 만든다).
  - 종료일이 지난 대회를 끝낸다.
  - 진행 중인 대회는 **내일까지** 가격을 만든다.
- **`ContestScheduler` (언제).** 08:50(장 전)과 15:40(장 후)에 본체를 부른다. 같은 일을 두 번 해도 안전하므로, 한 번이 실패해도 다음 번이 채운다.
- **대회마다 트랜잭션을 따로 쓴다.** 한 대회에서 실패해도 로그만 남기고 다른 대회는 처리된다.
- **테스트에서는 스케줄러를 끈다.** `src/test/resources/application.properties`에 `league.scheduling.enabled=false`를 넣었다. 실제 시계에 맞춰 배치가 돌면 테스트 결과가 실행 시각에 따라 달라진다.
- **서버가 여러 대라면.** 모두가 배치를 돈다. 결과는 같지만 일을 중복하므로 ShedLock 같은 분산 락을 붙인다(5주차 배포 때 판단).

## 15-7. 테스트 시각을 09:00으로 옮겼다

테스트 기본 시각을 10:00(12번 틱)에서 **09:00(0번 틱)**으로 옮겼다. 0번 틱 가격은 전일 종가이고, 첫날이면 상장 기준가 84,000원이다. 그래서 1·2주차 테스트의 금액 계산이 그대로 맞는다. 다른 틱의 체결 가격은 새 통합 테스트가 DB의 틱 가격과 비교해 확인한다.

## 결과

- 새 테스트 19개
  - 가격 엔진 단위 테스트 6개: 결정성, I8 1,000회, 1원 하한, 몬테카를로 표준편차
  - 지점 계산 6개
  - 통합 7개: 시작 시 1,872행, 재실행 안전, 다음 날 시가 = 전날 종가, 같은 시드 = 같은 가격, 종료일 제한, I11, 틱 가격 체결, 일일 배치
- **몬테카를로 확인.** 2,000일을 만들어 하루 수익률의 표준편차를 쟀다. 등급별 목표(1.0%·2.0%·3.5%)의 ±10% 안에 들었고, 평균은 0 근처였다.

## 외울 것

- **Q. 가격을 실시간으로 만들지 않고 미리 만든 이유는?**
  → 같은 시드면 같은 결과가 나와 재현과 검증이 된다. 재실행해도 안전하다. 체결 판단(지정가)이 "이미 있는 경로"를 읽기만 하면 된다. 대신 미래 가격이 DB에 있으므로 조회 조건으로 I11을 지킨다.
- **Q. 가격 모델은?**
  → 기하 브라운 운동이다. 틱마다 직전 가격에 e^(σZ − σ²/2)를 곱한다. 틱 σ는 하루 σ/√틱 수다. 전일 종가 ±30% 제한폭에서 자른다.
- **Q. 어떻게 같은 시드에서 같은 경로를 보장하나?**
  → 대회 시드 + 티커 + 날짜로 시드를 만든다. 알고리즘이 명세에 고정된 `java.util.Random`을 쓴다. DB id처럼 바뀔 수 있는 값은 섞지 않는다.
- **Q. 가격 저장에 JPA를 안 쓴 이유는?**
  → 하루 1,872행을 한 번 쓰고 바꾸지 않는다. 엔티티로 올리면 영속성 컨텍스트와 더티 체킹 비용만 든다. JdbcTemplate 배치로 넣는다.
- **Q. 배치를 두 번 돌리거나 서버 두 대가 동시에 돌리면?**
  → 이미 만든 날은 건너뛰고, INSERT도 `on conflict do nothing`이다. 경로가 결정적이라 누가 넣든 같은 값이다. 상태 전환도 이미 바뀐 대회는 건너뛴다.
- **Q. 주문 가격과 화면 가격을 왜 다른 방법으로 읽나?**
  → 주문은 서비스가 정한 (날짜, 틱)의 정확한 가격이 필요하다. 시계를 두 번 읽으면 틱 경계에서 어긋난다. 화면은 "지금 볼 수 있는 가장 최근 가격"이면 된다.
- **Q. 배치 로직을 스케줄러 클래스에 바로 쓰지 않은 이유는?**
  → 날짜를 인자로 받는 서비스로 빼야 테스트에서 아무 날이나 넣어 돌려 볼 수 있다. 스케줄러는 "언제 부를지"만 정한다.

---

# 16단계. 3주차②: 지정가 주문(묶기 → 정산 배치 → 체결·만료, 취소)

## 한 일

"80,000원 이하가 되면 10주 사 줘" 같은 지정가 주문을 만들었다.

1. 주문할 때 돈(매수)이나 주식(매도)을 **묶는다**.
2. 장 중에는 1분마다 정산 배치가 돈다. 가격 경로가 지정가에 **처음 닿은 틱**에 지정가로 체결한다.
3. 마지막 틱까지 안 닿으면 **만료**시키고 묶은 것을 돌려준다.
4. 대기 중인 주문은 **취소**할 수 있다.
5. 배치를 몇 번 돌려도, 취소와 겹쳐도 주문은 **정확히 한 번** 끝난다(I10).

| 만든 것 | 파일 |
|---|---|
| 현금 묶기·풀기·묶인 돈으로 내기 | `Participant.reserve`, `release`, `payFromReserved` |
| 주식 묶기·풀기·묶인 주식 팔기 | `Holding.reserve`, `release`, `sellReserved` |
| 요청에 주문 종류·지정가 추가 | `PlaceOrderRequest(type, limitPrice)` (`type`을 비우면 시장가) |
| 지정가 돈 계산 한 곳 | `order/LimitOrderAccounting` (묶기·체결·풀기) |
| 주문 하나 정산(트랜잭션 하나) | `order/LimitOrderExecutor` |
| 대회 전체 정산 | `order/LimitOrderSettlementService` |
| 처음 닿은 틱 찾기 | `PriceTickRepository.firstTouchTick` |
| 취소, 내 주문 목록 | `DELETE /api/contests/{id}/orders/{orderId}`, `GET /api/contests/{id}/orders` |
| 배치 연결 | `ContestScheduler` 장 중 1분마다, `runDaily`가 밀린 주문 정산 |

**체결 가격은 지정가로 정했다**(docs/01 6절의 현재 안). 그러면 매수 때 묶는 돈이 "지정가 × 수량 + 수수료"로 딱 정해지고, 체결 때 그 돈을 정확히 다 쓴다. 남는 돈을 계산해 돌려줄 필요가 없다. 닿은 틱의 가격으로 체결하도록 바꾸려면 `LimitOrderAccounting.fill`만 고치면 된다. 그때는 차액 반환이 추가된다.

## 16-1. 묶기: 원장에 남기지 않는 이동

```java
public void reserve(long amount) {      // Participant
  if (cash < amount) throw new BusinessException(INSUFFICIENT_CASH);
  cash -= amount;
  reservedCash += amount;
}
```

- **현금 → 묶인 현금은 원장에 쓰지 않는다.** I5는 `현금 + 묶인 현금 = 원장 합계`다. 묶기는 왼쪽 두 항 사이의 이동이라 합이 그대로다. 돈이 계좌 밖으로 나간 게 아니다.
- **체결 때만 원장에 남긴다.** 묶인 돈이 실제로 빠져나가는 순간이다(`payFromReserved` + 원장 BUY·FEE).
- **주식도 같다.** `reservedQuantity`를 늘린다. 팔 수 있는 수량 = 보유 − 묶인 수량이다. 그래서 묶인 주식은 시장가로도, 다른 지정가로도 팔 수 없다(I2).
- **묶인 것보다 많이 풀면 `IllegalStateException`을 던진다.** 사용자 잘못이 아니라 계산이 어긋난 프로그램 오류이기 때문이다.

## 16-2. 처음 닿은 틱 찾기

```sql
select tick_no from price_ticks
 where contest_id = ? and company_id = ? and trading_day = ?
   and tick_no between ? and ?          -- 주문한 틱 ~ 지금 틱(또는 마지막 틱)
   and price <= ?                        -- 매수. 매도는 price >= ?
 order by tick_no
 limit 1
```

- **매수는 가격 ≤ 지정가, 매도는 가격 ≥ 지정가.** "이 값 이하면 사겠다", "이 값 이상이면 팔겠다"이다.
- **시작은 주문한 틱이다.** 주문한 그 틱에 이미 조건이 맞으면 그 틱에 체결된다.
- **끝은 "지금 틱"이다.** 가격 경로가 미리 만들어져 있어 오후 가격도 DB에 있다. 그러나 10:00에 돌린 정산이 14:00 가격으로 체결하면 미래를 본 것이다. 그래서 끝 틱을 지금 틱으로 자른다(I11과 같은 원칙).
- **체결 시각은 가격이 닿은 틱의 시각이다.** 배치가 돈 시각이 아니다(`Contest.tickStartAt`). 배치가 3분 늦게 돌아도 기록은 정확하다.

## 16-3. I10: 정확히 한 번 끝나게

정산과 취소가 같은 주문에 동시에 달려들 수 있다. 서버 두 대가 배치를 동시에 돌릴 수도 있다. 막는 방법은 세 겹이다.

```java
@Transactional
public Outcome settle(Long orderId, TickPosition upTo) {
  Long pid = orderRepository.findParticipantIdByOrderId(orderId);
  // ① 계좌를 잠근다
  Participant p = participantRepository.findByIdForUpdate(pid);
  // ② 잠근 "뒤에" 읽고, 이미 끝났으면 건너뛴다
  Order order = orderRepository.findById(orderId);
  if (order.getStatus() != PENDING) return SKIPPED;
  ...
  // ③ 엔티티도 PENDING 이 아니면 예외(마지막 방어선)
  order.fill(...);
}
```

1. **같은 락.** 취소 API도 같은 계좌 락을 잡는다. 정산과 취소는 줄을 선다.
2. **잠근 뒤에 다시 읽기.** 락을 잡기 전에 읽은 주문은 그사이 다른 쪽이 바꿨을 수 있다. 그래서 정산 대상 목록은 id만 가져오고, 주문마다 락을 잡은 뒤 새로 읽는다.
3. **엔티티 규칙.** `fill`·`cancel`·`expire`는 PENDING이 아니면 예외를 던진다. 위 두 겹이 뚫려도 이중 체결 대신 롤백이 된다.

- **주문 하나 = 트랜잭션 하나.** 주문 1,000개를 한 트랜잭션으로 묶으면 락을 오래 잡는다. 하나가 실패하면 전부 롤백된다. 지금은 한 주문이 실패해도 대기 상태로 남아 다음 실행 때 다시 시도된다.
- **`LimitOrderExecutor`를 별도 빈으로 뺀 이유.** 같은 클래스 안에서 `@Transactional` 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 열리지 않는다(셀프 호출 문제).
- **락 방식 설정과 무관하게 정산과 취소는 비관적 락을 쓴다.** 하루 몇 번 도는 배치와 드문 취소라 기다림 비용이 작다. 정확성이 더 중요하다.

## 16-4. 언제 정산하나: 함수 하나, 부르는 곳 셋

`settle(contestId, now)`는 `now`에 볼 수 있는 가격(15단계의 `latestVisibleTick`)까지로 판단한다.

| 부르는 곳 | now | 결과 |
|---|---|---|
| 장 중 1분마다 | 지금 | 닿은 주문 체결, 나머지는 대기 |
| 15:30 이후 같은 배치 | 지금(마지막 틱이 보임) | 닿은 주문 체결, 나머지 **만료** |
| 다음 날 08:50 일일 배치 | 오늘 0시(어제 종가까지 보임) | 어제 밀린 주문을 어제 가격으로 마저 정산 |

- 만료 조건은 "마지막 틱까지 봤는데 안 닿음"이다. 시각이 아니라 가격 경로 위치로 판단한다. 그래서 배치가 늦게 돌아도, 다음 날 돌아도 같은 결과다.
- 대회 종료 처리 전에도 정산을 먼저 돌린다. 끝난 대회에 대기 주문이 남지 않는다.

## 16-5. 2주차 조건부 UPDATE에서 찾은 버그

지정가 접수는 계좌를 **엔티티로** 고친다(`participant.reserve`). 그런데 조건부 UPDATE 방식의 시장가는 계좌를 **SQL로** 고친다. 이 둘이 겹치면 문제가 생긴다.

```
A(지정가): 계좌 읽음 cash=1,000, version=5
B(시장가, 조건부): update cash = cash - 100 → 900   ← version 은 그대로 5
A: update cash=1,000-묶을 돈, version=6 where version=5   ← 통과! B 의 차감이 사라짐
```

`@Version` 검사는 "version이 바뀌었나"만 본다. SQL이 version을 안 올리면 검사를 통과해 버린다. 그래서 조건부 UPDATE 두 개에 `p.version = p.version + 1`을 추가했다. **같은 행을 두 가지 방법(엔티티, SQL)으로 고치면 서로의 보호 장치를 모를 수 있다.** 조건부 UPDATE를 기본값으로 고르지 않은 이유가 하나 더 생겼다.

## 16-6. 재요청 응답의 예외

1주차 원칙은 "같은 키 재요청은 처음과 같은 응답"이었다. 지정가는 처음엔 PENDING이었다가 나중에 FILLED가 될 수 있다. 재요청에는 **지금 상태**를 돌려준다. 같은 주문(같은 id)이라는 것은 같다. 처음 응답을 저장해 두었다가 그대로 돌려주는 방식도 있다. 하지만 "내 주문이 지금 어떤가"를 알려 주는 편이 클라이언트에 더 유용하다고 판단했다.

## 결과

- 새 테스트 21개(단위 7, 통합 14). 전체 테스트가 통과했다.
- 통합 테스트는 특정 가격을 외워 두지 않는다. DB의 틱 가격을 읽어 "몇 번 틱에 닿는지"를 계산한 뒤 정산 결과와 비교한다. 시드가 바뀌어도 테스트가 맞다.
- I10 경합 테스트: 지정가 20건에 대해 정산 4개와 취소 1개를 동시에 출발시켰다. 결과는 다음과 같았다.
  - 모든 주문이 체결·취소·만료 중 하나로 끝났다.
  - 원장 BUY 줄 수 = 체결 건수였다(이중 체결 없음).
  - 묶인 현금은 0이었고, I5가 맞았다.

## 외울 것

- **Q. 지정가 매수 때 돈을 왜 묶나?**
  → 체결은 나중에 배치가 한다. 그때 돈이 없으면 체결할 수 없다. 주문 시점에 확보해 둬야 "체결 가능한 주문"만 대기한다.
- **Q. 묶을 때 원장에 왜 안 남기나?**
  → I5는 `현금 + 묶인 현금 = 원장 합계`다. 묶기는 두 항 사이의 이동이라 합이 그대로다. 돈이 실제로 나가는 체결 때만 남긴다.
- **Q. 이중 체결을 어떻게 막았나?**
  → 정산과 취소가 같은 계좌 락을 잡는다. 락을 잡은 뒤 주문을 새로 읽어 PENDING인지 확인한다. 엔티티도 PENDING이 아니면 예외를 던진다. 동시 경합 테스트로 확인했다.
- **Q. 배치가 늦게 돌거나 하루 빠지면?**
  → 체결 틱은 가격 경로에서 찾고, 체결 시각도 그 틱의 시각으로 남긴다. 다음 날 배치가 어제 가격으로 마저 정산한다. 언제 돌든 결과가 같다.
- **Q. 장 중 정산이 미래 가격으로 체결하지 않게 하려면?**
  → 찾는 틱 범위의 끝을 "지금 틱"으로 자른다. 경로가 미리 있어도 지금까지 공개된 가격만 본다.
- **Q. 정산을 주문 하나씩 트랜잭션으로 나눈 이유는?**
  → 락을 짧게 잡고, 하나가 실패해도 나머지는 처리하기 위해서다. 실패한 주문은 대기로 남아 다음 실행 때 다시 시도된다.
- **Q. 조건부 UPDATE에 version 증가를 왜 넣었나?**
  → 엔티티로 고치는 쪽의 `@Version` 검사가 SQL 변경을 알아채게 하기 위해서다. 안 넣으면 SQL의 변경이 덮어써진다.

---

# 17단계. 3주차③: 가상 공시, 출석 보너스, 순위 스냅샷

## 한 일

3주차의 남은 기능 세 개를 만들었다.

1. **가상 공시(F6).** 가격을 만들 때 "그날의 공시"도 함께 정한다. 공시는 그 틱에 가격을 계단처럼 옮긴다. 공시 틱 전에는 API로 보이지 않는다(I11).
2. **출석 보너스(F11).** 진행 중인 대회의 거래일에 하루 한 번 50,000원을 준다. 동시에 10번 눌러도 한 번만 들어온다(I7).
3. **순위 스냅샷(F12).** 5분(틱)마다 모든 참가자의 총자산을 계산해 순위표 한 장으로 저장한다. 순위 조회는 이 스냅샷만 읽는다.

| 만든 것 | 파일 |
|---|---|
| 스키마: 공시 멱등 키, 순위 테이블 2개 | `db/migration/V3__disclosures_ranking.sql` |
| 그날 공시 정하기(순수 계산) | `market/DisclosurePlanner` |
| 가격 엔진에 충격 넣기 | `PriceEngine.generateDay(..., shockBpByTick)` |
| 가격과 공시를 같은 트랜잭션에 | `PriceGenerationService.ensureGeneratedThrough` |
| 공시 저장·조회 | `market/DisclosureRepository`, `DisclosureService`, `GET /api/contests/{id}/disclosures` |
| 출석 | `account/AttendanceRepository`, `AttendanceService`, `POST /api/contests/{id}/attendance` |
| 순위 스냅샷 찍기·조회 | `ranking/RankingRepository`, `RankingService`, `GET /api/contests/{id}/rankings`, `GET .../rankings/me` |
| 배치 연결 | `ContestScheduler` 장 중 5분마다(30초), `runDaily`가 전날 종가 스냅샷 |

## 17-1. 공시는 가격을 만들 때 함께 정한다

공시를 따로 만드는 배치를 두면 두 가지가 어긋날 수 있다.

- 공시는 났는데 가격은 그대로다.
- 가격은 급등했는데 설명하는 공시가 없다.

그래서 `PriceGenerationService`가 하루치 가격을 만들기 **직전에** 그날 공시를 정하고, 그 충격을 넣어 가격을 만들고, 둘을 **한 트랜잭션**으로 저장한다.

공시도 가격처럼 **결정적**이다. 같은 (대회 시드, 날짜) → 같은 공시. 난수 시드는 가격 엔진의 `seedFor`를 재사용하되, 티커 자리에 `"#DISCLOSURE"`를 넣었다. 티커로는 쓰일 수 없는 문자열이라 어떤 종목의 가격 난수열과도 겹치지 않는다.

```java
Random random = new Random(PriceEngine.seedFor(contestSeed, "#DISCLOSURE", day));
int count = random.nextInt(3);              // 하루 0~2건, 평균 1건
int tick = 1 + random.nextInt(ticks - 1);   // 0번 틱(시가 = 전일 종가)에는 내지 않는다
```

| 범위 | 확률 | 크기 | 예 |
|---|---|---|---|
| 회사 | 60% | ±3~12% | "도깨비게임즈, 신작·신제품 출시 연기" |
| 섹터 | 30% | ±2~7% | "메모리 가격 반등 전망" → 반도체·IT 4종목 |
| 시장 | 10% | ±1~3% | "기준 금리 동결" → 24종목 전체 |

상장 목록은 **티커 순으로 정렬한 뒤** 뽑는다. DB가 돌려주는 순서는 보장되지 않기 때문이다. 순서가 바뀌면 "3번째 회사"가 다른 회사가 된다. 테스트로 목록을 뒤집어 넣어도 같은 공시가 나오는지 확인했다.

## 17-2. 충격은 "계단"이다

```java
x = x * Math.exp(drift + sigma * random.nextGaussian());   // 평소 움직임
if (shockBpByTick != null && shockBpByTick[i] != 0) {
  x = x * (BP + shockBpByTick[i]) / BP;                    // 공시 틱: +500bp 면 ×1.05
}
// 그 다음 제한폭(I8) 안으로 자른다
```

- 공시 틱에 한 번 곱하고 끝이다. 이후 경로는 옮겨진 가격에서 이어진다. 효과가 사라지지 않는다.
- 충격이 있어도 **난수는 똑같이 뽑는다.** 그래서 같은 시드의 "충격 있는 경로"와 "없는 경로"는 공시 틱 전까지 완전히 같고, 그 뒤로는 정확히 (1 + 충격)배 차이가 난다. 테스트가 이것을 1원 오차로 확인한다.
- 제한폭 자르기는 충격 뒤에 한다. +90% 충격을 넣어도 ±30% 안에 머문다(I8 테스트).

docs/02에 적어 둔 "지속 틱 수"는 1로 정했다(한 틱에 바로 반영). 여러 틱에 나눠 반영하면 "공시를 보고 빨리 사면 이긴다"는 게임이 된다. 1차 범위에서는 공시를 판단 재료로만 쓰고 속도 경쟁은 만들지 않았다.

## 17-3. 크기는 몬테카를로로 정했다

공시가 너무 크면 공시 = 정답이라 게임이 싱겁다. 너무 작으면 잡음에 묻혀 공시가 의미 없다. 그래서 같은 시드로 "충격 있는 하루"와 "없는 하루"를 2,000번씩 만들어 **공시 방향대로 마감할 확률**을 쟀다(점심 무렵 틱 39에 공시).

| 변동성(하루 σ) | 3% | 5% | 10% | 12% |
|---|---|---|---|---|
| 낮음(1.0%) | 99.9% | 100% | 100% | 100% |
| 보통(2.0%) | 92.8% | 99.4% | 100% | 100% |
| 높음(3.5%) | 80.4% | 91.8% | 99.7% | 99.9% |

(값 = 공시 방향대로 마감한 비율. 평균 이동은 모든 칸에서 기대값 ln(1 + 충격)과 소수 넷째 자리까지 같았다. 예: 3% → 0.0296)

읽는 법:

- **평균 이동이 기대값과 같다.** 엔진이 충격을 정확히 그 크기만큼 넣는다는 뜻이다.
- **확률은 "충격 ÷ 하루 표준편차"로 정해진다.** 변동성 높은 종목에 3% 공시는 다섯 번 중 한 번은 반대로 끝난다. 공시를 믿고 샀다가 틀릴 수 있다.
- **변동성 낮은 종목은 3%만 돼도 거의 확실하다.** 처음 예상과 달랐던 부분이다. 하루 표준편차가 1%라 3%는 3σ다.

그래서 이렇게 정했다.

- 회사 공시 3~12%: 변동성 높은 종목의 작은 공시만 "애매한 신호"가 되고, 나머지는 강한 신호다. 공시가 하루 평균 1건이고 그중 60%만 회사 공시라, 한 종목이 회사 공시를 받는 날은 드물다(24종목 중 하나). 강한 신호가 자주 나오지는 않는다.
- 더 다듬을 수 있는 방법: 충격 크기를 "종목 변동성의 몇 배"로 정하면 모든 종목에서 확률이 같아진다. 1차 범위에서는 단순한 고정 범위로 두고, 이 표를 근거로 남겼다.

## 17-4. I11: 공시도 가격과 같은 기준으로 자른다

```sql
where d.contest_id = ? and (d.trading_day, d.tick_no) <= (?, ?)
```

15단계의 현재가 쿼리와 **같은 행 비교 조건**, 같은 `latestVisibleTick(now)`를 쓴다. 기준이 하나라서 "가격은 올랐는데 공시는 아직 안 보이는" 틈이 없다. 공시 틱의 가격과 공시는 같은 순간에 보인다.

내일 공시는 오늘 밤 배치가 미리 만들어 DB에 있다. 테스트로 오늘 23:00에 조회해도 오늘 공시만 나오는지 확인했다.

응답에는 **충격 크기(`impact_bp`)를 넣지 않았다.** 참가자에게는 정답이기 때문이다. DTO에 필드가 없으니 실수로 내보낼 수도 없다. 테스트가 JSON에 `impactBp`가 없는지 확인한다.

같은 날 배치가 두 번 돌면 공시가 두 벌 생길 수 있었다. 처음 스키마의 공시 테이블은 `bigserial` id뿐이라 중복을 막을 키가 없었다. V3에서 `seq`(그날 몇 번째 공시인지) 열과 `(contest_id, trading_day, seq)` 유니크를 추가하고 `on conflict do nothing`으로 넣는다. 가격 행과 같은 방법이다.

## 17-5. 출석: 판정은 DB 기본키에 맡긴다

```java
Participant p = participantRepository.findForUpdate(contestId, userId)   // 1. 계좌 잠금(주문과 같은 순서)
if (!attendanceRepository.insertIfAbsent(p.getId(), today)) {           // 2. 기본키가 판정
  throw new BusinessException(ErrorCode.ALREADY_ATTENDED);
}
p.deposit(bonus);                                                        // 3. 입금 + BONUS 원장
ledgerRepository.save(CashLedger.record(p, LedgerEntryType.BONUS, bonus, null));
```

```sql
insert into attendance (participant_id, attended_on) values (?, ?)
on conflict do nothing          -- 넣었으면 1행, 이미 있으면 0행
```

"있는지 보고 → 없으면 넣기"를 두 문장으로 나누면 그 사이에 다른 요청이 끼어든다(14단계의 갱신 손실과 같은 구조). 한 문장으로 넣고, **바뀐 행 수**로 처음인지 판단한다.

계좌 잠금도 잡는다. 어차피 현금을 바꾸기 때문이다. 잠금이 있으면 두 번째 요청은 첫 번째가 커밋할 때까지 기다렸다가 0행을 받는다. 잠금이 없어도 기본키가 막는다. 잠금 전략을 바꾸거나 새 경로가 생겨도 I7은 DB가 지킨다.

**잠그는 순서**도 신경 썼다. 주문·정산·취소는 모두 "계좌 먼저"다. 출석도 계좌를 먼저 잠근다. 두 트랜잭션이 서로 다른 순서로 두 자원을 잠그면 교착(deadlock)이 생긴다.

**날짜는 한국 시간이다.** `LocalDate.now(clock)`의 clock이 `Asia/Seoul`이다. 테스트: 12월 1일 23:59와 12월 2일 00:01에 출석한다. UTC로는 둘 다 12월 1일(14:59, 15:01)이다. 서버가 UTC 날짜를 썼다면 두 번째가 409가 됐을 것이다.

| 확인한 것 | 결과 |
|---|---|
| 첫 출석 | 201, 현금 +50,000, BONUS 원장, I5 성립 |
| 같은 날 두 번째 | 409 `ALREADY_ATTENDED`, 현금 그대로 |
| 다음 날 | 다시 받음 |
| 동시에 10번 | 성공 1, `ALREADY_ATTENDED` 9, I5 성립 |
| 시작 안 한 대회, 비참가자 | 409 `CONTEST_NOT_RUNNING`, 404 `NOT_PARTICIPANT` |

## 17-6. 순위: 쓰기는 배치, 읽기는 스냅샷

총자산(F12) = 현금 + 묶인 현금 + Σ(보유 수량 × 그 시점 가격).

조회할 때마다 계산하면 어떻게 될까? 참가자 1,000명 × 보유 종목 × 현재가를 순위 요청마다 계산해야 한다. 순위 조회는 장중 내내 몰리지만(docs/01 5절 가정: 조회 약 90 TPS), 순위는 가격이 바뀌는 5분마다만 바뀐다. 그래서 **5분마다 한 번 계산해 저장하고, 조회는 저장된 것만 읽는다.**

```
ranking_snapshots  (id, contest_id, trading_day, tick_no)  유니크 (contest_id, trading_day, tick_no)
ranking_entries    (snapshot_id, participant_id, rank, total_assets)  인덱스 (snapshot_id, rank)
```

스냅샷은 **SQL 한 문장**으로 찍는다.

```sql
insert into ranking_entries (snapshot_id, participant_id, rank, total_assets)
select ?, t.participant_id, rank() over (order by t.total desc), t.total
  from (select p.id as participant_id,
               (p.cash + p.reserved_cash
                 + coalesce(sum(h.quantity * coalesce(px.price, cl.base_price)), 0))::bigint as total
          from participants p
          left join holdings h on ...
          left join contest_listings cl on ...                      -- 틱 가격이 없으면 기준가
          left join (select distinct on (company_id) ...            -- 그 틱까지의 최신 가격(I11)
                       where (trading_day, tick_no) <= (?, ?)) px on ...
         where p.contest_id = ?
         group by p.id) t
```

- **왕복 1번.** 자바로 1,000명을 읽어 계산하고 1,000줄을 넣으면 왕복이 많다. DB 안에서 계산·정렬·저장까지 끝낸다.
- **한 시점.** READ COMMITTED에서도 한 문장은 시작 순간의 데이터로 끝까지 본다. 참가자마다 따로 읽으면 그 사이에 체결된 주문 때문에 사람마다 다른 순간으로 평가된다.
- **보유 수량에는 묶인 수량이 들어 있다.** 지정가 매도로 묶여도 아직 내 주식이다(`quantity`는 묶인 수량을 포함하고, 팔 수 있는 수량이 `quantity − reserved_quantity`다).
- **`rank()`** 는 동점이면 같은 순위를 준다(1, 2, 2, 4). `row_number()`였다면 동점자 중 누가 2등인지 임의로 정해진다.
- `sum(bigint)`의 결과는 PostgreSQL에서 `numeric`이다. `::bigint`로 바꿔 넣는다.

**멱등:** 스냅샷 머리 행을 `on conflict do nothing returning id`로 넣는다. id가 안 돌아오면 이미 찍은 틱이라 줄을 넣지 않는다. 머리와 줄은 한 트랜잭션이다. 머리만 있고 줄이 없는 "빈 순위표"가 보이지 않는다.

**언제 찍나:**

| 언제 | 기준 틱 | 왜 |
|---|---|---|
| 장 중 5분마다, **30초에** | 지금 틱 | 매분 0초의 지정가 정산이 끝난 상태를 찍기 위해 30초로 미뤘다 |
| 15:35 실행 | 그날 마지막 틱(종가) | 장 마감 뒤 첫 실행. 이후 실행은 같은 틱이라 건너뛴다 |
| 일일 배치(`runDaily`) | 전 거래일 종가 | 마감 스냅샷을 놓쳤어도 채운다. 종료일 다음 날이면 이것이 **최종 순위**다(대회를 끝내기 전에 찍는다) |

**수익률은 bp 정수로 준다.** `returnBp = floor(수익 × 10,000 / 초기 자금)`. 금액처럼 소수 없이 다루기 위해서다(1234 → 12.34%). 출석 보너스도 수익에 들어간다. 꾸준히 들어온 사람에게 주는 보상이다.

**남은 걱정(4주차 재료):** 5분마다 1,000줄이면 대회 하나에 하루 78장 × 1,000줄 = 78,000줄, 대회 10개면 하루 78만 줄이다. 지난 날의 장중 스냅샷은 지우고 종가 스냅샷만 남기는 정리 배치가 필요하다. 또 스냅샷 쿼리도 `distinct on` 현재가 서브쿼리를 쓴다. 대회가 길어질수록 읽는 가격 행이 늘어난다.

## 17-7. 테스트 도우미를 하나로 모았다

새 통합 테스트 세 개가 모두 "사용자 만들기 → 대회 만들기 → 참가 → 주문"을 한다. 테스트마다 복사하지 않고 `support/ApiFixture`로 모았다. 기존 테스트는 고치지 않았다(동작하는 테스트를 이번 일과 섞어 바꾸지 않는다).

`limit` 파라미터의 `@Min(1) @Max(100)`은 Spring 6.1부터 컨트롤러에서 바로 검사된다(`HandlerMethodValidationException`, 400). 클래스에 `@Validated`를 붙이면 예전 방식(AOP)으로 검사해 `ConstraintViolationException`이 나고, 이 예외는 지금 핸들러에서 500이 된다. 그래서 붙이지 않았다. 범위를 벗어난 `limit`이 400인지 테스트로 확인했다.

**겪은 일: 테스트 하나가 108초 걸렸다.** "종료일 다음 날 배치가 최종 순위를 찍는다" 테스트였다. 원인은 `runDaily`가 **진행 중인 모든 대회**를 처리한다는 점이다. 테스트 DB 컨테이너는 테스트 클래스끼리 공유된다. 앞 테스트들이 남긴 대회 수십 개의 가격까지 사흘치씩 만들고 있었다. 운영에서는 맞는 동작이다(배치는 모든 대회를 돌아야 한다). 그래서 코드는 두고, 그 테스트만 시작할 때 다른 대회를 끝난 상태로 바꾸게 했다.

## 결과

- 새 테스트 26개(단위 7, 통합 19). 전체 141개가 통과했다.
- 통합 테스트는 공시를 외워 두지 않는다. `DisclosurePlanner`로 그날 공시를 계산한 "정답지"와 DB를 비교한다. 공시가 있는 시드도 테스트가 찾는다.
- 공시가 들어간 종목의 하루 경로 78틱이 "엔진 + 그날 충격" 결과와 1원도 다르지 않았다.
- 출석 동시 10건: 성공 1건, 409 9건, I5 성립.
- 순위: 손으로 계산한 총자산(9,995,800원 등)과 스냅샷 값이 같았다. 동점 순위(1, 2, 2, 4)와 최종 순위(종료일 마지막 틱)도 확인했다.
- 전체 테스트는 약 19분 걸렸다. 테스트 러너의 기본 워치독 600초로는 끝나지 않아, 처음에 900초로 돌렸다가 강제 종료됐다. 30분으로 늘려 다시 돌렸다(부록 A).

## 외울 것

- **Q. 공시를 가격 생성과 같은 트랜잭션에서 만드는 이유는?**
  → 공시와 가격이 따로 저장되면 "공시만 있고 가격은 그대로"인 날이나 "설명 없는 급등"이 생긴다. 둘은 같은 사건의 두 얼굴이라 함께 커밋한다.
- **Q. 공시도 결정적이어야 하나?**
  → 가격이 공시 충격을 포함하기 때문이다. 공시가 실행마다 다르면 같은 시드에서 다른 가격이 나와 재실행 안전성이 깨진다.
- **Q. 미래 공시를 어떻게 숨기나?**
  → 현재가와 같은 `latestVisibleTick`과 `(trading_day, tick_no) <= (?, ?)` 조건을 쓴다. 기준이 하나라 가격과 공시가 같은 순간에 보인다. 충격 크기는 DTO에 아예 없다.
- **Q. 공시 크기를 어떻게 정했나?**
  → 같은 시드로 충격 있는/없는 경로를 2,000번씩 만들어 "공시 방향대로 마감할 확률"을 쟀다. 작은 공시는 변동성 높은 종목에서 애매하고, 큰 공시는 확실하도록 3~12%로 정했다.
- **Q. 출석 중복을 어떻게 막나?**
  → `(participant_id, attended_on)` 기본키 + `insert ... on conflict do nothing`. 바뀐 행 수가 0이면 이미 출석했다. 확인과 삽입이 한 문장이라 끼어들 틈이 없다. 동시 10건 테스트로 1건만 성공하는 것을 확인했다.
- **Q. 출석의 "하루"는 누구 기준인가?**
  → 한국 시간이다. Clock 빈의 시간대가 `Asia/Seoul`이다. 자정 직전·직후 테스트로 UTC와 다르게 동작하는 것을 확인했다.
- **Q. 순위를 왜 스냅샷으로 하나?**
  → 순위 조회는 많고 순위는 5분마다만 바뀐다. 매번 1,000명의 총자산을 계산하는 대신 5분마다 한 번 계산하고 조회는 저장된 결과만 읽는다.
- **Q. 스냅샷을 왜 SQL 한 문장으로 찍나?**
  → 왕복이 1번이고, 한 문장은 한 시점의 데이터를 보기 때문에 모든 참가자가 같은 순간으로 평가된다.
- **Q. `rank()`와 `row_number()`의 차이는?**
  → 동점일 때 `rank()`는 같은 순위(1, 2, 2, 4), `row_number()`는 임의로 다른 번호를 준다. 순위표에는 `rank()`가 맞다.
- **Q. 스냅샷 데이터가 너무 많아지지 않나?**
  → 대회 10개면 하루 78만 줄이다. 지난 날의 장중 스냅샷은 지우고 종가만 남기는 정리가 필요하다. 4주차에 다룬다.

---

# 18단계. 4주차①: 조회 성능(EXPLAIN으로 찾고, 고치고, 재기)

## 한 일

느릴 것 같은 쿼리 네 개를 실제 규모의 데이터로 재고 고쳤다.

| 실험 | 무엇 | 바꾼 것 | 전 → 후(서버 실행 시간 중앙값) | 읽은 블록(8KB) |
|---|---|---|---|---|
| A | 현재가(종목 24개의 최신 가격) | `distinct on` → `LATERAL` + `limit 1` | 166 ms → 1.1 ms | 3,055 → 98 |
| B | 순위 스냅샷의 총자산 계산 | A의 문장을 재사용 | 197 ms → 43 ms | 3,522 → 565 |
| C | 내 주문 목록(최근 50건) | 정렬을 `created_at desc, id desc`로, 인덱스를 같은 순서로 | 34 ms → 0.4 ms | 2,276 → 16 |
| D | 가격 하루치 INSERT(1,872행) | 드라이버 `reWriteBatchedInserts=true` | 744 ms → 484 ms | — |

- 실험 데이터(docs/01 5절 가정): 대회 10개가 30일째, 가격 561,600행, 참가자 10,000명, 보유 48,000행, 주문 105,000건.
- 시간은 실행할 때마다 두 배 가까이 흔들렸다(같은 노트북에서 다른 일이 돌기 때문이다). **읽은 블록 수**는 매번 같았다. 그래서 비교의 기준은 블록 수로 보고, 시간은 참고로 본다.
- C는 주문이 오래전에 몰린 참가자의 경우다. 주문 10건인 보통 참가자는 전후 모두 0.5 ms 정도로 차이가 없었다.

| 만든 것 | 파일 |
|---|---|
| 실험(기본 테스트에서는 건너뜀) | `src/test/.../perf/QueryPerformanceExperiment` |
| 현재가 문장 | `PriceTickRepository.LATEST_PRICES_SQL` |
| 순위 계산이 같은 문장을 재사용 | `RankingRepository.TOTALS_SQL`, `INSERT_ENTRIES_SQL` |
| 주문 목록 정렬 | `OrderRepository.findRecentWithCompany` |
| 인덱스 교체 | `db/migration/V4__orders_recent_index.sql` |
| 배치 INSERT 설정 | `application.yml`의 `hikari.data-source-properties.reWriteBatchedInserts` |

실행:

```bash
LEAGUE_EXPERIMENT=true TEST_TIMEOUT=900 ./run_tests.sh --tests '*QueryPerformanceExperiment'
grep -h '\[성능\]' build/test-results/test/*QueryPerformance*.xml   # 결과 줄
ls build/perf/                                                       # 실행 계획 전문
```

## 18-1. 실험 방법: 결과가 같은지 먼저, 그다음 계획과 시간

쿼리마다 같은 순서로 확인했다.

1. **예전 문장과 지금 문장의 결과가 같은가.** 빨라졌는데 답이 다르면 의미가 없다. 행을 정렬해 그대로 비교한다.
2. **`EXPLAIN (ANALYZE, BUFFERS)`.** 실제로 실행하고, 어떤 길(계획)로 몇 행을 읽었는지, 블록을 몇 개 건드렸는지 본다.
3. **30번 실행한 서버 실행 시간의 중앙값.** 앞에 5번은 데우기로 버린다. 평균 대신 중앙값을 쓰는 이유는 가끔 튀는 한 번에 끌려가지 않기 위해서다.

데이터는 앱으로 넣지 않고 SQL `generate_series`로 넣었다. 56만 행을 서비스로 만들면 몇 시간이 걸린다. 가격 값은 날짜·틱·종목마다 다르게 만들었다. 모두 같은 값이면 "결과가 같다"는 비교가 아무것도 확인하지 못한다.

이 실험은 `LEAGUE_EXPERIMENT=true`일 때만 돈다(`@EnabledIfEnvironmentVariable`). 데이터를 넣는 데만 3분이 걸려서, 평소 테스트에 넣으면 이미 19분인 전체 테스트가 더 길어진다.

## 18-2. A. 현재가: 인덱스 열 순서와 조건이 어긋났다

예전 문장:

```sql
select distinct on (company_id) company_id, price
  from price_ticks
 where contest_id = ? and (trading_day, tick_no) <= (?, ?)
 order by company_id, trading_day desc, tick_no desc
```

예전 계획(요약):

```
Unique  (rows=24)
  -> Sort  (rows=55,272)                         ← 5만 행을 정렬
       -> Bitmap Heap Scan on price_ticks  (rows=55,272, 블록 3,055)
            Index Cond: contest_id = 1 AND ROW(trading_day, tick_no) <= ROW(...)
```

기본키는 `(contest_id, company_id, trading_day, tick_no)`다. 조건은 `contest_id`와 `trading_day`에 걸려 있는데, 그 사이에 `company_id`가 끼어 있다. 그래서 인덱스는 `contest_id = 1`인 부분 전체(종목 24개 × 30일 × 78틱 ≈ 5.5만 행)를 훑을 수밖에 없다. 그 5.5만 행을 정렬한 뒤 종목마다 맨 앞 한 행만 남긴다. **24행을 얻으려고 5.5만 행을 읽는다.** 대회가 길어질수록 더 느려진다.

지금 문장:

```sql
select cl.company_id, p.price
  from contest_listings cl
  cross join lateral (
    select pt.price from price_ticks pt
     where pt.contest_id = cl.contest_id and pt.company_id = cl.company_id
       and (pt.trading_day, pt.tick_no) <= (?, ?)
     order by pt.trading_day desc, pt.tick_no desc
     limit 1
  ) p
 where cl.contest_id = ?
```

지금 계획(요약):

```
Nested Loop  (rows=24, 블록 98)
  -> Seq Scan on contest_listings  (rows=24)
  -> Limit  (rows=1, loops=24)
       -> Index Scan Backward using price_ticks_pkey  (rows=1, loops=24)
            Index Cond: contest_id = .. AND company_id = .. AND ROW(trading_day, tick_no) <= ROW(...)
```

- `LATERAL`은 "바깥 행마다 안쪽 쿼리를 한 번씩 실행하라"는 뜻이다. 종목 24개마다 안쪽 쿼리가 돈다.
- 안쪽 쿼리는 `contest_id`, `company_id`가 같고 그다음 `(trading_day, tick_no)`로 범위를 정한다. **기본키 열 순서와 정확히 같다.** 그래서 인덱스에서 그 위치를 바로 찾고, 뒤에서부터 한 칸만 읽고 멈춘다(`limit 1`).
- 결과: 블록 3,055 → 98(종목당 약 4블록). **대회가 며칠째든 거의 같다.**

새 인덱스를 만들지 않았다. 이미 있는 기본키를 제대로 쓰게 문장만 바꿨다. 인덱스는 쓰기를 느리게 하고 공간을 쓰므로, 있는 것을 먼저 활용한다.

## 18-3. B. 순위 계산: 같은 문장을 재사용해 함께 빨라졌다

순위 스냅샷(17단계)도 같은 `distinct on` 서브쿼리를 쓰고 있었다. 이제 `PriceTickRepository.LATEST_PRICES_SQL`을 CTE(`with px as (...)`)로 가져다 쓴다. 두 곳이 같은 문장을 쓰니 **"내 계좌의 평가액"과 "순위표의 총자산"이 같은 가격 기준**이라는 것도 코드로 보장된다.

남은 43 ms는 대부분 `holdings` 전체(48,000행)를 읽어 참가자 1,000명과 해시 조인하는 부분이다. 이 쿼리는 5분에 한 번 도는 배치라 지금은 충분하다. 대회가 100개로 늘면 `holdings`도 10배가 되니, 그때는 참가자 쪽에서 출발해 보유를 기본키로 찾는 계획이 나오는지 다시 본다.

## 18-4. C. 내 주문 목록: 인덱스를 맞췄는데 플래너가 안 썼다

예전: 쿼리는 `order by o.id desc limit 50`, 인덱스는 `(participant_id, created_at desc)`. 정렬과 인덱스 순서가 달랐다.

**첫 시도:** 인덱스를 `(participant_id, id desc)`로 바꿨다. 결과가 그대로였다. 계획을 보니 플래너는 새 인덱스를 쓰지 않았다.

```
Limit (rows=50)
  -> Index Scan Backward using orders_pkey on orders   ← 기본키를 뒤에서부터
       Filter: (participant_id = 1)
       Rows Removed by Filter: 99,990                   ← 남의 주문 10만 건을 건너뜀
```

**왜?** 플래너는 "이 참가자의 주문(전체의 약 5%)이 테이블 전체에 고르게 퍼져 있다"고 가정한다. 그러면 기본키(id)를 뒤에서부터 읽으면 약 1,000행 안에 50건이 나온다. 정렬도 필요 없다. 그래서 이 길이 싸 보인다. 그런데 실험 데이터의 이 참가자는 **주문이 오래전에 몰려 있다**(예전에 많이 거래하고 지금은 쉬는 사람). 뒤에서부터 읽으면 다른 사람들의 최근 주문 10만 건을 지나서야 이 사람 주문이 나온다. **통계가 "분포"는 알아도 "id와 참가자의 관계"는 모른다.**

> 처음 데이터에서는 이 참가자의 주문을 맨 마지막에 넣었다. 그러면 기본키를 뒤에서 읽자마자 50건이 나와서 문제가 보이지 않았다. 측정 데이터가 "최선의 경우"면 결론이 틀린다. 넣는 순서를 바꿔서 다시 쟀다.

**고친 것:** 정렬을 `created_at desc, id desc`로 바꾸고, 인덱스도 `(participant_id, created_at desc, id desc)`로 같은 순서로 만들었다(V4). 기본키는 `created_at` 순서를 만들어 줄 수 없다. 그래서 "기본키를 뒤에서 읽는" 길이 아예 후보에서 빠지고, 새 인덱스에서 이 참가자 부분의 앞 50건만 읽는다.

```
Limit (rows=50, 블록 16)
  -> Index Scan using idx_orders_participant_recent   ← 50행만 읽고 멈춤
       Index Cond: (participant_id = 1)
```

- 주문이 몰린 참가자: 34 ms → 0.4 ms, 블록 2,276 → 16.
- 주문 10건인 보통 참가자: 전후 모두 0.5 ms. 10건을 읽어 정렬하는 것은 원래 싸다.
- `id` 대신 `created_at`으로 정렬해도 결과(최근 50건)가 같은지 실험이 확인한다. 같은 시각이면 `id`로 순서를 정한다.

배운 것: **인덱스를 만들었다고 쓰이는 것이 아니다. 반드시 `EXPLAIN`으로 실제 계획을 확인한다.** 그리고 쿼리가 다른 길을 고를 여지를 없애는 것(정렬을 인덱스만 만들 수 있는 순서로)이 플래너 힌트보다 확실하다. PostgreSQL에는 힌트 문법이 기본으로 없다.

## 18-5. D. 배치 INSERT: 기대보다 작은 효과

가격 하루치(1,872행)를 넣는 `batchUpdate`에 PostgreSQL 드라이버 설정 `reWriteBatchedInserts=true`를 켰다. 드라이버가 `insert ... values (...)` 1,872개를 `values (...), (...), ...` 여러 행 INSERT 몇 개로 합쳐 보낸다.

| | 1회차 ~ 5회차(ms) | 중앙값 |
|---|---|---|
| 기본 | 733, 744, 909, 765, 740 | 744 |
| 합쳐 보내기 | 639, 431, 484, 470, 485 | 484 |

- 약 35% 빨라졌다. 기대(몇 배)보다 작다. JDBC 배치는 원래도 문장마다 응답을 기다리지 않고 한꺼번에 보내기 때문이다. 합쳐 보내기는 서버가 문장을 해석하는 횟수를 줄이는 정도다.
- 측정에서 실수할 뻔한 것: 처음에는 측정마다 **연결을 새로 맺었다**. 그러면 연결 비용(인증 등)이 두 방식 모두에 섞여 차이가 흐려진다. 연결 하나를 계속 쓰고, 측정 전에 한 번씩 데운 뒤 다시 쟀다.
- **대가:** 드라이버가 행마다 "몇 행 들어갔는지"를 알려 주지 않는다(`SUCCESS_NO_INFO`). 그래서 `insertDay`가 넣은 행 수를 돌려주던 것을 없앴다. 쓰는 곳이 없었다.

## 18-6. 확인만 하고 고치지 않은 것

- **공시 조회**: `(contest_id, trading_day, tick_no)` 인덱스가 있다. 정렬의 마지막 열 `seq`가 인덱스에 없지만, 대회 하나의 공시는 하루 1건꼴이라 정렬할 행이 수십 개뿐이다.
- **지정가 정산 대상 찾기**: `(status, trading_day)` 인덱스로 `PENDING`만 찾는다. 체결된 주문이 아무리 쌓여도 대기 중인 것만 읽는다.
- **원장 합계(I5 확인)**: `(participant_id, id)` 인덱스가 있다.

## 결과

- 실험 4개(쿼리 5종). 모든 경우 예전·지금 결과가 같은 것을 먼저 확인했다.
- 전체 테스트 141개 통과(실험 4개는 건너뜀).
- 전체 테스트 시간이 이번에는 약 19분 → 8분이었다. 테스트마다 현재가·순위 조회와 가격 INSERT를 많이 하니 이번 변경의 영향일 수 있다. 다만 같은 노트북의 부하에 따라 시간이 크게 흔들리는 것을 실험에서 봤으므로, 원인을 이것 하나로 단정하지 않는다.

## 외울 것

- **Q. 인덱스가 있는데 왜 느렸나?**
  → 인덱스는 앞 열부터 순서대로만 범위를 좁힐 수 있다. 기본키 `(contest_id, company_id, trading_day, tick_no)`에서 조건이 `contest_id`와 `trading_day`에만 걸리면, 가운데 `company_id` 때문에 `trading_day`로 좁히지 못하고 대회 전체를 읽는다.
- **Q. 어떻게 고쳤나?**
  → 종목마다(`LATERAL`) `contest_id`, `company_id`를 같게 두고 `(trading_day, tick_no)` 범위의 마지막 1행만 찾았다. 인덱스 열 순서와 같아서 종목당 몇 블록만 읽는다. 새 인덱스 없이 3,055블록 → 98블록.
- **Q. `distinct on`이 나쁜가?**
  → 아니다. 읽는 행이 적으면 좋은 도구다. 여기서는 "종목마다 1행"을 얻으려고 대회 전체를 정렬하는 게 문제였다. 데이터가 늘수록 같이 느려지는 문장인지가 핵심이다.
- **Q. 인덱스를 만들었는데 플래너가 안 쓰면?**
  → `EXPLAIN`으로 이유를 본다. 이번에는 플래너가 "주문이 고르게 퍼져 있다"고 가정해 기본키 역순 탐색을 골랐다. 정렬을 `created_at desc, id desc`로 바꿔 기본키로는 만들 수 없는 순서로 만들자, 플래너가 새 인덱스를 쓸 수밖에 없게 됐다.
- **Q. 성능을 어떻게 비교했나?**
  → 결과가 같은지 먼저 확인하고, `EXPLAIN (ANALYZE, BUFFERS)`로 계획과 읽은 블록을 보고, 30번 실행의 중앙값을 쟀다. 시간은 환경에 따라 흔들려서 블록 수를 주 기준으로 삼았다.
- **Q. 실험 데이터에서 조심한 점은?**
  → 데이터가 우연히 최선의 경우가 되지 않게 했다. 주문이 많은 참가자의 주문을 처음에 맨 뒤에 넣었더니 문제가 숨었다. 순서를 바꿔 다시 쟀다. 배치 INSERT도 연결 비용이 섞이지 않게 연결 하나로 다시 쟀다.
- **Q. `reWriteBatchedInserts`의 효과와 대가는?**
  → 여러 INSERT를 여러 행 INSERT로 합쳐 서버의 해석 횟수를 줄인다. 이번에는 약 35%. 대가로 드라이버가 행별 결과 수를 주지 않는다.

---

# 19단계. 4주차②: 조회 캐시(없음 vs 서버 메모리 vs Redis)

## 한 일

"많이 읽고, 5분(틱)마다만 바뀌는" 조회 두 개에 캐시를 붙이고, 캐시 없음·서버 메모리(Caffeine)·Redis를 같은 부하로 비교했다.

| 캐시한 것 | 키 | 왜 무효화가 필요 없나 |
|---|---|---|
| 현재가(내 계좌 평가에 씀) | `대회:날짜:틱` | 그 틱의 가격은 한 번 만들면 바뀌지 않는다 |
| 순위표 상위 N명 | `스냅샷id:N` | 스냅샷은 머리와 줄이 한 트랜잭션으로 커밋되고 그 뒤로 바뀌지 않는다 |

**결론: 기본값은 서버 메모리(Caffeine).** Redis는 설정 한 줄(`CACHE_TYPE=redis`)로 바꿀 수 있게 남겼다.

| 만든 것 | 파일 |
|---|---|
| 캐시 도우미(넣을지 판단, 고장 나면 DB로) | `common/ReadCache`, `common/CacheConfig` |
| 현재가 캐시 | `TickPriceProvider.currentPrices` |
| 순위표 캐시 | `RankingService.topEntries` |
| "다 만들어진 가격인가" 판단 | `PriceTickRepository.LatestPrices(prices, complete)` |
| 설정 | `application.yml`의 `spring.cache.*`, `spring.data.redis.*` |
| 로컬 Redis | `docker-compose.yml`의 `redis`(포트 6380) |
| 부하 비교 | `scripts/cache-bench.sh`, `scripts/perf-seed.sql` |

## 19-1. 먼저 물었다: 캐시가 정말 필요한가

18단계 뒤로 현재가 쿼리는 1 ms대다. 캐시는 공짜가 아니다.

- **낡은 값:** 원본이 바뀌었는데 캐시에 예전 값이 남는다.
- **무효화:** 그래서 "언제 지울지"를 정해야 하고, 이게 캐시 버그의 대부분이다.
- **운영:** Redis를 쓰면 서버가 하나 더 생긴다. 죽으면 어떻게 할지도 정해야 한다.

그래서 붙이기 전에 비용을 줄일 방법부터 정했다. **키에 "바뀌지 않는 버전"을 넣는다.** 현재가는 `(대회, 날짜, 틱)`, 순위는 `스냅샷 id`. 그 버전의 값은 영원히 같으니 무효화가 필요 없다. 새 틱이 되면 키가 바뀌어 자연히 새로 읽고, 지난 키는 크기 제한·TTL(10분)로 사라진다.

## 19-2. 함정: "아직 다 만들어지지 않은 값"

현재가는 "지금 틱까지의 최신 가격"이다. 그런데 배치가 밀려 **오늘 가격이 아직 없으면** 어제 종가로 채운다(15단계 설계). 이 결과를 `오늘:12틱` 키로 캐시하면, 나중에 오늘 가격이 생겨도 10분 동안 어제 종가가 보인다. **버전이 키에 있어도, 그 버전의 데이터가 완성되지 않았으면 캐시하면 안 된다.**

그래서 현재가 쿼리가 가격과 함께 그 가격이 **어느 (날짜, 틱)에서 왔는지**도 돌려주게 했다. 모든 종목이 정확히 요청한 틱에서 왔을 때만 `complete = true`이고, 그때만 캐시에 넣는다.

```java
PriceBoard board = readCache.get(ReadCache.LATEST_PRICES, contestId + ":" + p.day() + ":" + p.tick(),
    () -> { ... return new PriceBoard(prices, latest.complete()); },
    PriceBoard::complete);          // ← 다 만들어진 것만 넣는다
```

테스트(`ReadCacheTest`): 12월 2일 가격을 안 만든 채 10:00에 조회 → 12월 1일 종가가 보인다. 그다음 가격을 만들고 **같은 시각에** 다시 조회 → 바로 12월 2일 12틱 가격이 보인다.

순위표는 이 문제가 없다. 스냅샷 머리와 줄이 한 트랜잭션이라 보이는 순간 이미 완성돼 있다. 단, "가장 최근 스냅샷이 무엇인지"는 캐시하지 않았다. 새 스냅샷이 생기면 바로 바뀌어야 하기 때문이다(인덱스 한 번이라 싸다).

## 19-3. 캐시가 고장 나도 요청은 실패하지 않는다

```java
try {
  Cache.ValueWrapper hit = cache.get(key);
  ...
} catch (RuntimeException e) {
  log.warn("캐시 읽기 실패, DB 로 대신 읽는다 ...");
  return loader.get();
}
```

- 캐시는 빠르게 하는 장치이지 정답을 가진 곳이 아니다. Redis가 죽으면 DB에서 읽는다.
- **타임아웃을 짧게:** Redis 클라이언트(Lettuce)의 기본 명령 타임아웃은 60초다. 그대로면 Redis가 멈췄을 때 요청마다 60초를 기다린다. `spring.data.redis.timeout: 500ms`로 줄였다.
- 테스트(`RedisReadCacheTest`): Redis 컨테이너를 **일시정지**(죽은 척)시키고 조회 → DB 값과 같고, 3초 안에 끝나고, `error` 카운터가 오른다.
- 캐시 결과는 `league.cache{cache, result=hit|miss|error}` 카운터로 남긴다. 적중률을 운영 중에 볼 수 있다.

`@Cacheable` 애노테이션을 쓰지 않은 이유: "다 만들어진 것만 넣기"(`unless`로 가능하긴 하다)와 "고장 나면 DB로"(`CacheErrorHandler`를 따로 등록해야 한다)를 한 클래스에서 눈에 보이게 하고 싶었다. 대신 쓰는 곳이 두 군데뿐이라 코드가 길어지지 않았다.

## 19-4. 측정: 같은 데이터, 같은 부하, 저장소만 바꿔서

`scripts/cache-bench.sh`가 세 방식마다:

1. 빈 DB `league_perf`를 새로 만들고 앱을 띄운다(시계 2027-01-30 10:20 고정, SQL 로그 끔).
2. `perf-seed.sql`로 30일째 대회 하나, 참가자 1,000명, 순위 스냅샷 1장을 넣는다.
3. `ab`(ApacheBench)로 1,000번 데운 뒤 10,000번, 동시 연결 20개로 요청한다.
4. 요청 전후 `pg_stat_database`의 블록·트랜잭션 수를 읽어 **요청 한 번이 DB에서 읽은 블록**을 계산한다.

두 번 돌렸다.

| 방식 | 순위표 req/s (1회 / 2회) | 순위표 블록/요청 | 내 계좌 req/s (1회 / 2회) | 내 계좌 블록/요청 |
|---|---|---|---|---|
| 캐시 없음 | 196 / 154 | 48.7 | 194 / 212 | 109 |
| 서버 메모리 | 203 / 356 | 10.2 | 293 / 300 | 12.1 |
| Redis | 302 / 311 | 10.2 | 210 / 274 | 12.0 |

응답 시간 중앙값(2회차): 캐시 없음 86~105 ms → 서버 메모리 50~61 ms, Redis 58~68 ms.

읽는 법:

- **DB가 하는 일은 두 번 모두 똑같이 5~9배 줄었다**(블록/요청 48.7 → 10.2, 109 → 12). 이 숫자는 흔들리지 않는다. 남은 10~12블록은 대회 조회, 참가자 조회, 최근 스냅샷 찾기다.
- **처리량과 응답 시간은 흔들렸다.** 1회차에는 서버 메모리 순위표의 99%가 661 ms로 튀었다. 측정한 노트북은 4코어 1.4 GHz이고, `ab`·앱·DB·Redis(도커)가 한 기계에서 CPU를 나눠 쓴다. 측정 중 부하 평균(load average)이 40~60이었다. 그래서 시간은 "대체로 1.3~2배 좋아졌다" 정도로만 읽는다.
- **DB가 1 ms대인데 응답이 50~100 ms다.** 지금 병목은 DB가 아니라 CPU(직렬화, 스레드, 다른 프로세스)다. 캐시로 줄인 것은 "DB 부하"이고, 응답 시간 자체를 더 줄이려면 앱 쪽을 봐야 한다. 5주차 k6는 앱과 부하 발생기를 다른 기계에 두고 잰다.
- **서버 메모리와 Redis는 비슷했다.** Redis는 매 요청 네트워크 왕복과 직렬화(바이트 ↔ 객체)가 더해진다. 대신 서버 여러 대가 캐시를 함께 쓴다.

## 19-5. 고른 것: 서버 메모리(Caffeine)

| | 서버 메모리 | Redis |
|---|---|---|
| 속도 | 네트워크 없음 | 왕복 + 직렬화 |
| 서버 여러 대 | 서버마다 따로 캐시 | 함께 씀 |
| 서버가 다시 뜨면 | 비어서 시작 | 남아 있음 |
| 운영 | 없음 | 서버 하나 더(고장·메모리·보안) |

Redis의 장점은 "여러 서버가 같은 값을 본다"와 "재시작해도 남는다"다. 이 캐시에서는 둘 다 크지 않다.

- **같은 값:** 키에 버전이 있고 그 값은 바뀌지 않는다. 서버마다 따로 들고 있어도 같은 키면 같은 값이다. 서버마다 다른 값을 볼 일이 없다.
- **비어서 시작:** 캐시가 비면 서버마다 틱당 한 번 DB를 읽는다. 서버 10대면 틱(5분)마다 10번이다. 큰 비용이 아니다.

그래서 기본값을 서버 메모리로 정했다. 사용자별 세션, 여러 서버가 함께 세야 하는 요청 제한(rate limit)처럼 "서버끼리 공유해야 하는 상태"가 생기면 그때 Redis를 쓴다.

**Redis에 저장하는 형식:** 지금은 Spring 기본값인 자바 직렬화다. 그래서 캐시에 넣는 값(`PriceBoard`, `RankingRepository.Entry`)이 `Serializable`이다. 클래스 모양이 바뀌면 예전에 저장한 바이트를 못 읽으므로, 키 앞에 `league:v1:`을 붙였다. 형식이 바뀌면 `v2`로 올려 예전 값과 섞이지 않게 한다. Redis를 실제로 쓰게 되면 JSON으로 바꾸는 것을 검토한다.

## 19-6. 테스트도 캐시를 켠 채로 돈다

`src/test/resources/application.properties`에 `spring.cache.type=simple`을 넣었다. 기존 테스트 141개가 모두 캐시를 거친다. 캐시가 낡은 값을 돌려주는 버그가 있으면, 시계를 옮겨 가며 가격·순위를 확인하는 기존 테스트가 잡는다.

## 결과

- 새 테스트 5개(서버 메모리 캐시 3, Redis 2). 전체 146개 통과(성능 실험 4개는 건너뜀). 기존 테스트도 모두 캐시를 거쳐 통과했다.
- 부하 비교 두 번: DB 블록/요청은 두 번 모두 5~9배 줄었다. 처리량은 1.3~2배 좋아졌지만 측정 기계 부하 때문에 흔들렸다.

## 외울 것

- **Q. 무엇을 캐시했고 키는 무엇인가?**
  → 현재가(`대회:날짜:틱`)와 순위표(`스냅샷id:N`). 둘 다 그 버전의 값이 바뀌지 않아 무효화가 필요 없다.
- **Q. 그래도 낡은 값이 생길 수 있었던 곳은?**
  → 오늘 가격이 아직 없어 어제 종가로 채운 결과. 키는 "오늘 12틱"인데 값은 어제 것이다. 가격이 정확히 그 틱에서 왔을 때만 넣도록 했다.
- **Q. Redis가 죽으면?**
  → 경고를 남기고 DB에서 읽는다. 타임아웃을 60초에서 500 ms로 줄여 오래 기다리지 않는다. 컨테이너를 일시정지시키는 테스트로 확인했다.
- **Q. 왜 Redis가 아니라 서버 메모리인가?**
  → 키에 버전이 있어 서버마다 따로 들고 있어도 값이 같다. 캐시가 비어도 서버마다 틱당 한 번 DB를 읽을 뿐이다. 네트워크 왕복과 서버 하나를 더 운영하는 비용을 낼 이유가 없었다.
- **Q. 캐시 효과를 어떻게 쟀나?**
  → 같은 데이터·같은 부하로 저장소만 바꿔 `ab`를 돌리고, `pg_stat_database`로 요청당 DB 블록을 셌다. DB 일은 5~9배 줄었다. 응답 시간은 측정 기계가 CPU 부족이라 흔들려서 참고로만 봤다.
- **Q. 캐시를 붙였는데 응답이 여전히 50 ms인 이유는?**
  → DB는 이미 1 ms대였다. 병목은 같은 기계에서 돌던 부하 발생기·앱·DB가 CPU를 나눠 쓴 것이다. 캐시는 DB 부하를 줄였고, 응답 시간은 다른 곳에서 결정된다.

---

# 20단계. 5주차①: 순위 스냅샷 정리, Docker 이미지, CI

## 한 일

1. **순위 스냅샷 정리 배치.** 지난 날의 장중 스냅샷을 지우고 날마다 마지막 한 장만 남긴다.
2. **앱 Docker 이미지.** JRE만 든 이미지에 jar를 계층별로 나눠 넣었다. root가 아닌 사용자로 실행하고, 헬스체크를 붙였다.
3. **compose로 앱까지 띄우기.** `--profile app`을 줄 때만 앱 컨테이너가 뜬다.
4. **CI(GitHub Actions).** main에 올리거나 PR을 열면 테스트 → jar → 이미지 빌드까지 확인한다.

| 만든 것 | 파일 |
|---|---|
| 정리 쿼리 | `RankingRepository.pruneIntradayBefore` |
| 정리 서비스·배치 연결 | `RankingService.pruneIntraday`, `ContestLifecycleService.runDaily` |
| 앱 이미지 | `Dockerfile`, `.dockerignore` |
| 앱·DB·Redis 함께 띄우기 | `docker-compose.yml`(`app` 서비스, postgres 헬스체크) |
| CI | `.github/workflows/ci.yml` |

## 20-1. 스냅샷 정리: 무엇을 남기나

17단계의 "남은 걱정"이었다. 5분마다 1,000줄이면 대회 하나에 하루 78,000줄, 대회 10개면 하루 78만 줄이 쌓인다.

남기는 것:

- **오늘** 스냅샷은 모두 남긴다(장중 순위 조회에 쓴다).
- **지난 날**은 그날 **마지막 틱**의 스냅샷 한 장만 남긴다. 보통 종가 스냅샷이다. 날짜별 순위 추이는 그대로 볼 수 있다.
- 끝난 대회의 최종 순위(종료일 마지막 틱)도 그래서 남는다.

```sql
select s.id from ranking_snapshots s
 where s.contest_id = ? and s.trading_day < ?          -- 오늘보다 앞선 날
   and s.tick_no < (select max(s2.tick_no) from ranking_snapshots s2   -- 그날 마지막이 아닌 것
                     where s2.contest_id = s.contest_id and s2.trading_day = s.trading_day)
```

- **지우는 순서:** `ranking_entries`(줄)를 먼저, `ranking_snapshots`(머리)를 나중에. 줄이 머리를 외래 키로 가리키기 때문이다. 두 문장은 한 트랜잭션이다.
- **두 번 계산해도 같은가:** 지울 대상을 두 문장이 각각 다시 계산한다. 지난 날에는 새 스냅샷이 생기지 않으므로 같다.
- **조회에 영향이 없는가:** 가장 최근 스냅샷은 항상 오늘 것이거나 지난 날의 마지막 것이라 지워지지 않는다. 순위표 캐시(19단계)도 스냅샷 id가 키라, 지워진 스냅샷은 애초에 조회되지 않는다.
- **언제:** 일일 배치(`runDaily`)가 전 거래일 종가 스냅샷을 찍은 **바로 다음**에 정리한다. 순서가 반대면 "그날 마지막"이 아직 종가가 아니어서, 장중 마지막 스냅샷이 남고 종가가 나중에 추가된다(지워지지는 않지만 한 장이 더 남는다).

테스트: 12/1에 0·12·77틱, 12/2에 0·12틱 스냅샷 → 12/2 기준 정리 → `12/1:77, 12/2:0, 12/2:12`만 남는다. 다시 돌리면 0장. 순위 조회는 여전히 12/2 12틱.

## 20-2. Docker 이미지: JRE만, 계층을 나눠서

```dockerfile
FROM eclipse-temurin:17-jre-alpine AS extract
COPY ${JAR} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
COPY --from=extract /work/extracted/dependencies/ ./           # 라이브러리(약 70MB, 거의 안 바뀜)
COPY --from=extract /work/extracted/spring-boot-loader/ ./
COPY --from=extract /work/extracted/snapshot-dependencies/ ./
COPY --from=extract /work/extracted/application/ ./            # 내 코드(631KB, 자주 바뀜)
USER app
```

- **jar는 밖에서 만든다.** 이미지 안에서 Gradle로 빌드하면 JDK 이미지와 의존성 다운로드가 매번 필요하다. 빌드는 CI·로컬의 Gradle 캐시를 쓰고, 실행 이미지에는 JRE만 넣는다.
- **계층 나누기:** Docker 이미지는 `COPY` 한 줄이 한 계층이고, 내용이 같으면 다시 만들지도 올리지도 않는다. jar 하나를 통째로 넣으면 코드 한 줄만 고쳐도 70MB를 새로 올린다. 나눠 넣으면 **631KB 계층 하나만** 바뀐다(`docker history`로 확인).
- **root가 아닌 사용자:** 컨테이너 안에서 앱이 뚫려도 root 권한을 갖지 않게 한다. 실행 중 `id -un`이 `app`인 것을 확인했다.
- **메모리:** `-XX:MaxRAMPercentage=75`. 컨테이너 메모리 한도의 75%를 힙으로 쓴다. 고정값(`-Xmx`)을 쓰면 한도를 바꿀 때마다 이미지를 고쳐야 한다.
- **헬스체크:** `/actuator/health`가 `UP`인지 10초마다 본다. compose·배포 환경이 "떠 있지만 고장 난" 컨테이너를 알아챈다.
- **시간대:** 컨테이너 시계는 UTC다. 업무 날짜는 코드가 `Asia/Seoul`을 직접 쓰므로(`TimeConfig`) 영향이 없다. 로그 시각만 한국 시간으로 보이게 `-Duser.timezone=Asia/Seoul`을 줬다.

이미지 크기는 392MB다. 기본 이미지(JRE + alpine)가 257MB, 라이브러리가 약 135MB다.

**확인한 것(스모크 테스트):** 임시 DB `league_smoke`에 이미지를 붙여 띄우고 curl로 한 바퀴 돌았다. 헬스 → 사용자 → 대회 생성·시작 → 참가 → 시장가 매수 → 같은 키로 다시 보내기(같은 결과) → 출석 → 내 계좌 → 공시 → 순위. 현금이 `10,000,000 − 3 × 84,188 − 수수료 1,262 + 보너스 50,000 = 9,796,174`원으로 맞았다. 끝나고 임시 DB는 지웠다.

**겪은 일:** 컨테이너 로그에 "캐시가 통계를 기록하지 않는다"는 경고가 나왔다. Caffeine은 `recordStats`를 켜야 적중률 같은 지표를 남긴다. 설정에 추가했다.

## 20-3. compose: 같은 파일로 두 가지 쓰임

```bash
docker compose up -d                 # DB·Redis만. 앱은 IDE·bootRun으로(개발할 때)
docker compose --profile app up -d   # 앱 컨테이너까지(이미지가 정말 도는지 볼 때)
```

- `profiles: ["app"]`인 서비스는 프로필을 줄 때만 뜬다. 평소 개발 흐름(앱은 IDE에서)을 바꾸지 않으려고 이렇게 했다.
- 앱은 `depends_on: postgres: condition: service_healthy`로 DB가 **준비된 뒤에** 뜬다. 그냥 `depends_on`은 컨테이너가 "시작됐는지"만 보고, DB가 접속을 받을 수 있는지는 모른다. 그래서 postgres에 `pg_isready` 헬스체크를 붙였다.
- 컨테이너끼리는 서비스 이름과 **컨테이너 안 포트**로 찾는다(`postgres:5432`, `redis:6379`). 호스트에서 쓰는 5433·6380은 바깥용이다.

## 20-4. CI: 올릴 때마다 테스트와 이미지 빌드

```yaml
on: { push: { branches: [main] }, pull_request: {} }
steps:
  - actions/checkout@v4
  - actions/setup-java@v4 (temurin 17)
  - gradle/actions/setup-gradle@v4      # Gradle·의존성 캐시
  - ./gradlew test                       # Testcontainers 가 PostgreSQL·Redis 를 띄운다
  - upload-artifact (테스트 리포트, 실패해도)
  - ./gradlew bootJar
  - docker build
```

- GitHub의 ubuntu 러너에는 Docker가 있어서 Testcontainers 테스트가 그대로 돈다.
- 테스트 리포트는 `if: always()`로 실패해도 올린다. 실패했을 때야말로 리포트가 필요하다.
- `timeout-minutes: 40`: 로컬 전체 테스트가 10~20분이다. 무언가 멈추면 40분에 끊는다(이 프로젝트의 워치독 원칙과 같다).
- 이미지는 아직 어디에도 올리지(push) 않는다. 올릴 곳(레지스트리)과 배포할 곳은 다음 단계에서 정한다.
- 이 파일은 로컬에서 실행해 볼 수 없다. 커밋해서 GitHub에 올라간 뒤 첫 실행 결과를 확인한다.

## 20-5. CI 첫 실행이 잡은 버그: 장 시간이 9시간 밀려 저장됐다

CI 첫 실행에서 통합 테스트가 거의 모두 실패했다. 노트북에서는 148개가 모두 통과했는데.

**원인 찾기:**

1. 실패한 테스트들이 모두 `DataIntegrityViolationException`이었다. 가장 단순한 `EntityMappingTest`가 대회 하나를 저장하다 실패했다.
2. 노트북과 CI의 차이를 생각했다. **JVM 기본 시간대**가 다르다. 노트북은 Asia/Seoul, GitHub 러너는 UTC다.
3. 테스트 JVM을 UTC로 돌리게 하자(`build.gradle`의 `systemProperty 'user.timezone', 'UTC'`) 노트북에서도 똑같이 실패했다. DB 오류 내용:

```
Failing row contains (1, 12월 리그, SCHEDULED, 2026-12-01, 2026-12-31, 18:00:00, 00:30:00, ...)
```

장 시간 09:00·15:30이 **18:00·00:30**으로 저장됐다. 9시간 밀렸고, 마감이 자정을 넘어 `check (open_time < close_time)`에 걸렸다.

**왜:** 1주차 설정에 `hibernate.jdbc.time_zone: Asia/Seoul`이 있었다. 이 설정은 Hibernate가 시간 값을 DB에 넣을 때 "JVM 시간대 → Asia/Seoul"로 바꾸라는 뜻이다. JVM이 Asia/Seoul이면 바꿀 것이 없어 아무 일도 없었다. JVM이 UTC면 시간대가 없는 값인 `LocalTime`까지 9시간을 더해 버렸다.

**고친 것:** 그 설정을 지웠다. 시간대가 없는 값(`LocalDate`, `LocalTime`)은 그대로 저장되고, 시각(`Instant`)은 `timestamptz`라 원래 시간대와 무관하다. 업무 날짜는 코드가 `Asia/Seoul`을 직접 쓴다(`TimeConfig`).

**다시 안 생기게:** 테스트는 이제 **언제나 UTC JVM**에서 돈다. 노트북에서만 통과하는 시간대 버그를 노트북에서 잡는다.

**운이 좋았던 점:** 스모크 테스트에서 이 버그가 안 보인 이유는 Dockerfile이 `-Duser.timezone=Asia/Seoul`을 줬기 때문이다. 그 옵션이 없는 서버에 배포했다면 장 시간이 틀린 대회가 만들어졌을 것이다. CI가 배포 전에 잡았다. **"내 컴퓨터에서는 된다"를 믿지 않으려고 CI를 만드는 것**이라는 걸 첫날 겪었다.

## 결과

- 새 테스트 2개(정리 배치). 전체 148개 통과(성능 실험 4개는 건너뜀). 테스트 JVM을 UTC로 바꾼 뒤에도 모두 통과.
- 앱 이미지 빌드·실행·스모크 테스트 통과.
- CI 첫 실행은 실패했다(20-5). 고친 커밋(`2212ef8`)은 CI를 통과했다.

## 외울 것

- **Q. 스냅샷을 왜, 어떻게 지우나?**
  → 5분마다 1,000줄이라 대회 10개면 하루 78만 줄이다. 지난 날은 마지막 한 장(종가)만 남기고 지운다. 줄을 먼저, 머리를 나중에 한 트랜잭션으로 지운다. 최근 스냅샷은 지워지지 않아 조회에 영향이 없다.
- **Q. Dockerfile에서 jar를 왜 계층으로 나눴나?**
  → Docker는 계층 단위로 캐시하고 올린다. 라이브러리(거의 안 바뀜)와 내 코드(자주 바뀜)를 나누면 코드를 고쳐도 631KB 계층만 다시 만든다.
- **Q. 이미지 안에서 빌드하지 않은 이유는?**
  → 실행 이미지에는 JRE만 있으면 된다. 빌드는 Gradle 캐시가 있는 CI·로컬에서 하는 편이 빠르고, 이미지도 작다.
- **Q. 컨테이너를 root로 돌리면 안 되는 이유는?**
  → 앱이 뚫리면 공격자가 컨테이너 안에서 root 권한을 갖는다. 전용 사용자로 돌려 피해 범위를 줄인다.
- **Q. `depends_on`만으로 부족한 이유는?**
  → 컨테이너가 시작됐는지만 본다. DB가 접속을 받을 준비가 됐는지는 헬스체크(`pg_isready`)와 `condition: service_healthy`로 기다려야 한다.
- **Q. 컨테이너에서 힙 크기는 어떻게 정했나?**
  → `MaxRAMPercentage=75`. 컨테이너 메모리 한도에 비례하므로 한도를 바꿔도 이미지를 고칠 필요가 없다.
- **Q. CI에서 처음 잡은 버그는?**
  → `hibernate.jdbc.time_zone: Asia/Seoul` 때문에, JVM이 UTC인 CI에서 장 시간(`LocalTime`)이 9시간 밀려 저장됐다(09:00 → 18:00). 설정을 지우고, 테스트를 항상 UTC JVM으로 돌려 노트북에서도 잡히게 했다.
- **Q. CI에서 무엇을 확인하나?**
  → 테스트(실제 PostgreSQL·Redis 컨테이너로), jar 빌드, 이미지 빌드. 실패해도 테스트 리포트를 올린다.

---

# 21단계. 5주차②: 배포 준비(AWS EC2 + RDS)와 k6 부하 테스트

## 한 일

1. **배포할 곳을 정했다.** AWS EC2(앱 한 대) + RDS(PostgreSQL). 이미지는 GitHub Container Registry(ghcr.io)에 올린다. 계정·결제가 필요한 서버 만들기는 직접 하고, 그 전까지 할 수 있는 것을 모두 준비했다(순서는 [docs/05](docs/05-배포-AWS.md)).
2. **운영 설정(`prod` 프로필).** DB 주소·비밀번호·관리자 토큰을 환경 변수로만 받는다. 빠뜨리면 앱이 뜨지 않는다.
3. **관리자 API 보호.** `/api/admin/**`는 `X-Admin-Token` 헤더가 맞아야 통과한다.
4. **배포 스크립트.** 새 이미지를 띄우고 헬스 체크를 기다린다. 실패하면 직전 이미지로 자동으로 되돌린다. 로컬에서 세 가지 경우로 리허설했다.
5. **CI가 이미지를 올린다.** main에 올라온 커밋마다 `ghcr.io/jeffkm/mock-invest-league-api:<커밋 sha>`로 올린다.
6. **k6 부하 테스트.** 앱을 운영 설정 그대로, CPU 2개·메모리 700MB(t3.micro와 비슷하게)로 묶어서 쳤다. **과부하 때 서버가 스스로 무너지는 문제**를 찾아 고쳤다.
7. **순위 스냅샷을 바로 찍는 관리자 API.** 부하 테스트가 빈 순위표가 아니라 실제 1,000줄을 읽게 하려고 만들었다. 운영 점검에도 쓴다.

| 만든 것 | 파일 |
|---|---|
| 운영 설정 | `src/main/resources/application-prod.yml` |
| 관리자 토큰 검사 | `common/AdminTokenInterceptor`, `common/WebConfig`, `ErrorCode.ADMIN_UNAUTHORIZED` |
| 커넥션 부족 → 503 | `GlobalExceptionHandler.handleBusy`, `ErrorCode.SERVER_BUSY` |
| 예외 로그 형식 | `application.yml`의 `logging.exception-conversion-word` |
| 수동 순위 스냅샷 | `POST /api/admin/contests/{id}/ranking-snapshots`(`ContestLifecycleService.snapshotNow`) |
| 운영 compose·환경 변수 예시 | `deploy/docker-compose.yml`, `deploy/.env.example` |
| EC2 첫 설정 | `deploy/ec2-setup.sh` |
| 배포·되돌리기 | `deploy/deploy.sh` |
| 이미지 올리기 | `.github/workflows/ci.yml`(main일 때만) |
| 부하 테스트 | `scripts/k6/league.js`, `scripts/load-test.sh` |
| 배포 순서 문서 | `docs/05-배포-AWS.md` |
| 테스트 | `AdminTokenTest`(4), `GlobalExceptionHandlerTest`(2), `RankingSnapshotTest`(+1) |

## 21-1. 어디에, 왜: EC2 + RDS, 이미지는 ghcr.io

| 고른 것 | 다른 후보 | 고른 이유 |
|---|---|---|
| EC2 한 대에 Docker | ECS·Fargate, PaaS(Fly.io, Render) | 구성이 단순하고, 서버·네트워크·보안 그룹을 직접 다뤄 본다. 면접에서 자주 묻는 구성이다 |
| RDS(PostgreSQL 16) | EC2에 Postgres 같이 띄우기 | 백업·패치·복구를 AWS가 맡는다. 앱 서버가 죽어도 데이터는 따로 있다 |
| 캐시는 서버 메모리 그대로 | ElastiCache(Redis) | 서버가 한 대라 필요 없다(19단계). 비용도 아낀다 |
| ghcr.io | ECR | CI에 AWS 키를 넣지 않아도 된다. 저장소의 `GITHUB_TOKEN`만으로 올린다 |
| t3.micro(x86) | t4g.micro(ARM, 더 쌈) | CI가 x86 이미지를 만든다. ARM으로 가려면 다중 아키텍처 빌드가 필요하다 |

비용은 정가로 24시간 켜 두면 한 달 약 $30~37이지만, **무료 플랜 크레딧(최대 $200, 6개월)에서 빠지므로 실제로 내는 돈은 0원이다.** 무료 플랜은 유료로 바꾸지 않는 한 청구하지 않고, 크레딧이나 기간이 끝나면 계정을 닫는다(docs/05 2절). 크레딧을 오래 쓰려고 데모할 때만 켠다.

## 21-2. 운영 설정: 빠뜨리면 뜨지 않게

```yaml
# application-prod.yml (SPRING_PROFILES_ACTIVE=prod)
spring.datasource:
  url: ${DB_URL}              # 기본값 없음
  password: ${DB_PASSWORD}    # 기본값 없음
  hikari.connection-timeout: 3000
server.shutdown: graceful
league.admin.token: ${ADMIN_TOKEN}
management.endpoints.web.exposure.include: ${ACTUATOR_EXPOSE:health,info}
logging.level.org.hibernate.SQL: info
```

- **기본값 없는 `${...}`.** 기본 설정에는 로컬 도커용 비밀번호(`league`)가 있다. 운영에서 환경 변수를 빠뜨렸을 때 그 값으로 조용히 떠서 엉뚱한 DB에 붙는 것보다, **아예 안 뜨는 편이 안전하다.** 리허설에서 `ADMIN_TOKEN`을 빼고 띄우자 `Could not resolve placeholder 'ADMIN_TOKEN'`으로 멈췄다.
- **커넥션 대기 3초.** 기본값 30초면 DB가 바쁠 때 요청이 30초씩 매달린다. 빨리 실패시키고 503으로 알린다(21-6).
- **graceful shutdown.** 배포로 컨테이너를 내릴 때 새 요청은 막고, 처리 중인 주문은 끝낸 뒤 내려간다(최대 20초). compose의 `stop_grace_period: 30s`는 그보다 길게 잡았다.
- **metrics는 밖에 열지 않는다.** 지표에는 내부 정보(쿼리 수, 메모리)가 있다. 부하 테스트 때만 환경 변수로 잠깐 연다.
- **SQL 로그 끄기.** 기본 설정은 N+1 실험용으로 SQL을 모두 찍는다(debug). 요청마다 디스크에 쓰므로 운영에서는 끈다.

## 21-3. 관리자 API 보호: 토큰 하나, 상수 시간 비교

인터넷에 올리면 누구나 `/api/admin/contests`로 대회를 만들고 시작할 수 있다. 로그인 기능을 만들기 전에, 관리자 경로만 토큰으로 막았다.

```java
// AdminTokenInterceptor
String given = request.getHeader("X-Admin-Token");
if (given == null || !MessageDigest.isEqual(expected, given.getBytes(UTF_8))) {
  throw new BusinessException(ErrorCode.ADMIN_UNAUTHORIZED);   // → 401, ApiResponse 형식
}
```

- **경로 하나로 건다.** 1주차에 관리자 API를 `/api/admin`으로 따로 둔 덕에 `addPathPatterns("/api/admin/**")` 한 줄이면 된다.
- **`MessageDigest.isEqual`.** `String.equals`는 앞에서부터 비교하다 다른 글자에서 멈춘다. 응답 시간 차이로 토큰을 한 글자씩 알아낼 여지가 생긴다(타이밍 공격). `isEqual`은 길이가 같으면 끝까지 비교한다.
- **로컬에서는 끈다.** 토큰을 비워 두면 검사하지 않고 경고만 남긴다. Swagger로 바로 눌러 보던 개발 흐름을 바꾸지 않으려고.
- **인터셉터에서 던진 예외도** `@RestControllerAdvice`가 받는다. 그래서 401도 다른 오류와 같은 `ApiResponse` 형식이다.

테스트: 토큰 없음 401, 한 글자 틀림 401, 맞으면 201, 관리자 경로가 아니면 검사 안 함.

## 21-4. 배포 스크립트: 띄우고, 확인하고, 안 되면 되돌린다

```bash
./deploy.sh ghcr.io/jeffkm/mock-invest-league-api:<sha>
# 1) 이미지 먼저 받기(실패하면 지금 앱은 그대로) 2) 컨테이너 교체 3) healthy 될 때까지 최대 180초
# 4) 성공 → .deployed-image 에 기록  5) 실패 → 로그 50줄, 직전 이미지로 되돌림
```

- **`latest`를 쓰지 않는다.** `latest`는 "지금 서버에 뜬 게 어느 커밋인가"에 답하지 못하고, 되돌릴 곳도 가리키지 못한다. 커밋 sha 태그는 둘 다 정확하다.
- **헬스 체크가 판정한다.** 컨테이너가 "떠 있다"와 "요청을 받을 수 있다"는 다르다. Dockerfile의 `HEALTHCHECK`(`/actuator/health`가 UP인지)를 Docker가 기록하고, 스크립트는 그 상태만 본다.
- **처음 배포가 실패하면 앱을 멈춘다.** `restart: unless-stopped` 때문에 설정이 틀린 앱이 죽었다 살아나기를 끝없이 반복했다(리허설에서 발견). 되돌릴 곳이 없으면 멈추게 고쳤다.

**로컬 리허설**(실제 RDS 대신 로컬 Postgres의 임시 DB):

| 경우 | 결과 |
|---|---|
| `ADMIN_TOKEN` 빠뜨림 | 앱이 뜨지 않음 → 스크립트 실패(종료 코드 1) |
| 정상 | 78초 만에 healthy. 헬스 UP, metrics 404(닫힘), 토큰 없이 관리자 API 401, 토큰 있으면 201, SQL 로그 0줄 |
| 깨진 이미지(헬스 체크에 응답하지 않음) | 60초 뒤 실패 판정 → 직전 이미지로 되돌림 → healthy |

**되돌리기의 한계: DB 마이그레이션.** 이미지는 되돌려도 Flyway가 바꾼 스키마는 되돌아가지 않는다. 그래서 마이그레이션은 **예전 코드도 견디게** 만든다. 컬럼 추가는 괜찮다(예전 코드는 모르는 컬럼을 무시한다). 컬럼 삭제·이름 바꾸기는 "새 컬럼 추가 → 코드 전환 배포 → 예전 컬럼 삭제"처럼 두 번에 나눈다.

## 21-5. CI가 이미지를 올린다

```yaml
permissions: { contents: read, packages: write }
- name: 이미지 올리기(main 에 올렸을 때만)
  if: github.event_name == 'push' && github.ref == 'refs/heads/main'
  run: |
    IMAGE="ghcr.io/${GITHUB_REPOSITORY,,}"     # JeffKM → jeffkm(레지스트리 경로는 소문자만)
    echo "$GHCR_TOKEN" | docker login ghcr.io -u "${{ github.actor }}" --password-stdin
    docker push "$IMAGE:${{ github.sha }}"
```

- **PR에서는 올리지 않는다.** 테스트와 이미지 빌드까지만 한다. 리뷰 전 코드가 레지스트리에 쌓이지 않게.
- **권한은 필요한 만큼만.** `packages: write` 하나. 키를 따로 만들지 않고 실행마다 발급되는 `GITHUB_TOKEN`을 쓴다.
- 저장소가 비공개라 이미지도 비공개다. 서버는 `read:packages` 권한만 있는 토큰으로 로그인해서 받는다.
- 서버에 자동으로 배포하는 것(CD)은 아직 하지 않았다. CI가 서버에 들어갈 권한(SSH 키·SSM)을 갖게 되는 것이라, 서버를 만든 뒤 따로 정한다.

## 21-6. k6: 무엇을, 어떻게 쳤나

```
워밍업 30초(목표의 절반 속도) → 측정 2분
주문: 시장가 매수 80%, 체결 안 될 낮은 지정가 매수 20%
조회: 내 계좌 40%, 내 주문 30%, 순위표 20%, 공시 10%
통과 기준: 실패율 1% 미만, p95 주문 300ms·조회 200ms 미만
```

- **`constant-arrival-rate`.** 초당 N건을 정해진 시각에 보낸다. 사용자 수(VU)를 고정하고 "응답이 오면 다음 요청"을 보내면, 서버가 느려질수록 요청을 덜 보내서 실제보다 좋아 보인다(coordinated omission). 실제 사용자는 서버 사정을 봐주지 않는다.
- **준비(setup).** 대회 하나, 사용자 1,000명 참가, 순위 스냅샷 한 장. 50건씩 동시에 보낸다(`http.batch`).
- **시계 고정.** 운영 설정 그대로 띄우되 `LEAGUE_CLOCK_FIXED=2026-12-01T10:00:00`(화요일 장중)을 줬다. 밤에도 주문이 받아진다.
- **앱 컨테이너는 CPU 2개, 메모리 700MB.** t3.micro(2 vCPU, 1GB)와 비슷하게. DB는 묶지 않았다(RDS는 따로 있으므로).
- **같이 적은 것.** 5초마다 앱·DB 컨테이너 CPU, DB 트랜잭션 수, 커넥션 대기, GC 멈춤, 그리고 **이 맥의 load average**(21-8).
- **k6 설치.** Intel 맥이라 Homebrew에 미리 빌드된 k6가 없어 소스 빌드가 8분 넘게 걸렸다. 공식 릴리스 zip(v2.3.0)을 받아 체크섬을 확인하고 `~/.local/bin/k6`에 두었다.

## 21-7. 찾은 문제: 과부하가 오류 로그를 부르고, 오류 로그가 과부하를 키운다

처음 돌린 결과(가정의 1배 = 초당 120건)는 무너졌다. **실패 64%, p95 55초.**

**JFR(JDK Flight Recorder)로 본 것:**

```
스레드가 잠금을 기다린 시간 합계: 1,086초 (측정 3분 남짓 동안)
그중 1,003초: logback PackagingDataCalculator → 클래스 로더 잠금
CPU 표본이 가장 많이 잡힌 앱 코드: GlobalExceptionHandler.handleUnexpected
```

**고리:**

1. 요청이 몰려 DB 커넥션(10개)이 잠깐 모자란다.
2. 3초 기다린 요청이 `CannotCreateTransactionException`으로 실패한다.
3. 이 예외가 "처리되지 않은 예외"로 500 + **스택 트레이스**로 로그에 찍힌다.
4. Spring Boot 기본 로그 형식(`%wEx`)은 스택 줄마다 `~[spring-core-6.2.12.jar:6.2.12]`처럼 jar 이름·버전을 붙인다. 이걸 구하려고 줄마다 클래스를 찾는데, **클래스 로더의 전역 잠금**을 잡는다.
5. 수백 개 스레드가 그 잠금 앞에 줄을 선다. 그동안 잡고 있던 DB 커넥션도 늦게 돌려준다 → 1로 돌아간다.

**고친 것 두 가지:**

```java
// 1) 커넥션을 못 얻으면 503 + Retry-After, 로그는 한 줄
@ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class})
public ResponseEntity<ApiResponse<Void>> handleBusy(NestedRuntimeException e) {
  log.warn("DB 커넥션 부족으로 503: {}", e.getMostSpecificCause().getMessage());
  return ResponseEntity.status(503).header(RETRY_AFTER, "1").body(...SERVER_BUSY...);
}
```

```yaml
# 2) 예외 로그에서 jar 정보 계산을 뺀다(모든 프로필)
logging.exception-conversion-word: "%ex"
```

- **500이 아니라 503인 이유.** 500은 "서버 버그", 503은 "지금 바쁘니 잠시 뒤 다시"다. 클라이언트·로드 밸런서가 다르게 다룬다(503은 재시도, 500은 알림). 커넥션 부족은 버그가 아니라 용량 문제다.
- **스택 트레이스를 안 남기는 이유.** 원인이 이미 분명하다(풀 고갈). 한 줄이면 충분하고, 과부하 상황에서 로그 비용을 키우지 않는다.

**효과(이 맥의 부하와 상관없는 지표로):**

| | 고치기 전 | 고친 뒤 |
|---|---|---|
| 잠금 대기 합계(JFR) | 1,086초 | 5.1초 |
| 그중 jar 정보 계산 | 1,003초 | 0초 |
| 실패 응답 | 500 + 스택 트레이스 | 전부 503, 스택 트레이스 0줄 |

## 21-8. 측정 결과와, 측정이 틀릴 뻔한 이야기

모두 앱 CPU 2개·메모리 700MB·커넥션 10개, 측정 2분(워밍업 30초 뒤).

| 실행 | 목표(초당) | 주문 p95 | 조회 p95 | 실패율 | 이 맥의 load average |
|---|---|---|---|---|---|
| 고치기 전, 1배 | 120 | 55,035ms | 54,177ms | 64% | (기록 전) |
| 고친 뒤, 1배 | 120 | 14,655ms | 14,261ms | 26% | 끝날 때 31 |
| 고친 뒤, 0.5배 | 60 | 832ms | 474ms | 0% | 약 20 |
| 0.5배, GC를 G1로 | 60 | 89ms | 30ms | 0% | 8.7 |
| 0.5배, GC 원래대로(SerialGC) | 60 | **63ms** | **19ms** | 0% | 7 |
| 1배 | 120 | 3,054ms | 3,024ms | 4.7%(전부 503) | 14 → 18 |
| 1배, **CPU 4개**·메모리 1.4GB | 120 | 335ms | **172ms** | **0%** | 12 |

**틀릴 뻔한 것.** 0.5배에서 p95가 832ms였고 GC 멈춤이 최대 1.2초였다. 컨테이너 메모리가 1.8GB보다 작으면 JVM이 SerialGC를 고르기 때문에(`-XX:+PrintFlagsFinal`로 확인), "GC를 G1으로 바꾸면 된다"고 보고 바꿨더니 89ms로 좋아졌다. 그런데 **SerialGC로 한 번 더 돌리자 63ms로 더 좋았다.** 그사이 이 맥의 load average가 20에서 7로 내려가 있었다. 좋아진 것은 GC가 아니라 **맥이 한가해진 것**이었다. 이 맥은 Unity 에디터 같은 다른 작업도 돌고 있다. 그래서:

- 설정을 바꾸면 **원래 설정으로 한 번 더**(A → B → A) 돌려 확인한다.
- 러너가 실행 전후 load average를 결과에 같이 적게 했다.
- 수정 효과는 맥 부하와 상관없는 지표(JFR 잠금 대기, 500 개수)로 확인했다(21-7).

**읽은 것:**

- 이 맥에서 CPU 2개짜리 앱은 **초당 약 60건은 여유, 100건 근처에서 포화**다. CPU가 2코어 한도에 붙어 있었고 DB CPU는 여유가 있었다. 요청 하나에 CPU 약 20ms를 쓴다(1.4GHz 노트북, 다른 작업과 나눠 씀).
- CPU를 두 배로 주니 1배(초당 120건)를 실패 없이 받았다. 병목이 앱 CPU라는 뜻이다.
- **가정의 2~3배(초당 240~360건)는 이 맥에서 재지 않았다.** 1배에서 이미 포화이므로 숫자가 의미 없다. 실제 EC2에서 부하 발생기를 다른 기계로 두고 다시 잰다(docs/05 9절).
- 프로파일에서 한 군데가 튀지는 않았다. CPU는 Spring MVC·Hibernate·JSON·로그 같은 프레임워크 기본 비용에 고르게 나뉘었다. 눈에 띈 후보 하나는 Spring Data의 메서드 이름 쿼리(`findBy...`)다. Criteria로 만들어지는데, Hibernate는 Criteria의 실행 계획을 기본으로 캐시하지 않아(`hibernate.criteria.plan_cache_enabled=false`) 매번 SQL로 다시 바꾼다. 표본의 약 5%였다. 이 맥의 측정 잡음(같은 설정에서 실패율 4.7%~26%)보다 작아 효과를 잴 수 없어서 바꾸지 않았다.

## 결과

- 새 테스트 7개(관리자 토큰 4, 503 응답 2, 수동 스냅샷 1). 전체 155개 통과(성능 실험 4개는 건너뜀), UTC JVM, 10분 23초.
- 배포 리허설 3가지 통과. k6로 과부하 시 무너지는 고리를 찾아 끊었다.
- 직전 커밋(`2212ef8`, 시간대 버그 수정)이 CI를 통과한 것을 확인했다.
- 실제 AWS 서버는 아직 없다. 만들고 올리는 순서는 docs/05.

## 외울 것

- **Q. 어디에 어떻게 배포하나?**
  → EC2 한 대에 Docker로 앱을 띄우고 DB는 RDS다. CI가 main 커밋마다 이미지를 ghcr.io에 커밋 sha 태그로 올리고, 서버에서 `deploy.sh <태그>`로 바꾼다. 캐시는 서버 메모리라 Redis는 없다.
- **Q. 배포가 실패하면?**
  → 스크립트가 헬스 체크를 최대 180초 기다리고, healthy가 안 되면 직전 이미지로 되돌린다. 이미지를 먼저 받아 두므로 받기 실패로 서버가 멈추지도 않는다.
- **Q. 이미지 태그를 `latest`로 안 쓰는 이유는?**
  → 서버에 뜬 코드가 어느 커밋인지, 되돌릴 곳이 어디인지 알 수 없다. 커밋 sha는 둘 다 정확하다.
- **Q. 되돌릴 때 DB 스키마는?**
  → 되돌아가지 않는다. 그래서 마이그레이션은 예전 코드도 견디게 만든다. 컬럼 삭제·이름 바꾸기는 추가 → 전환 → 삭제로 나눠 배포한다.
- **Q. 비밀값은 어떻게 다루나?**
  → 서버의 `.env`(권한 600, git 제외)에서 환경 변수로 넣는다. 운영 설정은 기본값 없는 `${...}`라 빠뜨리면 앱이 뜨지 않는다. 다음 단계는 SSM Parameter Store 같은 비밀 저장소다.
- **Q. 관리자 API는 어떻게 막았나?**
  → `/api/admin/**`에 인터셉터로 `X-Admin-Token`을 검사한다. 비교는 `MessageDigest.isEqual`로 해서 응답 시간으로 토큰을 알아낼 수 없게 했다.
- **Q. k6에서 `constant-arrival-rate`를 쓴 이유는?**
  → 정해진 속도로 계속 보낸다. 사용자 수를 고정하면 서버가 느려질수록 요청이 줄어 결과가 실제보다 좋아 보인다(coordinated omission).
- **Q. 부하 테스트에서 무엇을 찾았나?**
  → 커넥션이 모자라 생긴 예외가 500 + 스택 트레이스로 찍히는데, 기본 로그 형식이 스택 줄마다 jar 정보를 구하느라 클래스 로더 잠금을 잡았다. 그 잠금 대기가 1,086초였고, 느려진 요청이 커넥션을 더 오래 잡아 악순환이 됐다. 503 + 한 줄 로그로 바꾸고 jar 정보 계산을 끄자 잠금 대기가 5초로 줄었다.
- **Q. 과부하일 때 500 대신 503을 주는 이유는?**
  → 버그가 아니라 용량 문제이고, 클라이언트가 다시 시도해도 되는 상황이다. `Retry-After`로 언제 다시 올지도 알린다.
- **Q. 측정에서 조심한 것은?**
  → 같은 기계에서 다른 작업이 결과를 흔들었다. GC를 바꿔서 좋아진 줄 알았는데, 원래 설정으로 다시 돌리니 더 좋았다. 맥이 한가해진 것이었다. 그래서 A→B→A로 확인하고, load average를 같이 적고, 수정 효과는 JFR 잠금 대기처럼 기계 부하와 무관한 지표로 본다.
- **Q. 서버 한 대가 얼마나 받나?**
  → 이 노트북에서 CPU 2개로는 초당 약 60건이 여유, 100건 근처에서 포화였다. CPU 4개로는 초당 120건을 실패 없이 받았다. 병목은 앱 CPU였다. 실제 EC2 수치는 배포 뒤 다른 기계에서 다시 잰다.
- **Q. 컨테이너에서 JVM이 SerialGC를 쓴 이유는?**
  → 메모리 1.8GB 미만(또는 CPU 1개)이면 JVM이 "서버급 기계가 아니다"라고 보고 SerialGC를 고른다. 이번 측정에서는 GC가 병목이 아니어서 바꾸지 않았다.
- **Q. graceful shutdown은 왜?**
  → 배포로 컨테이너를 내릴 때 처리 중인 주문이 중간에 끊기지 않게, 새 요청만 막고 진행 중인 요청은 끝낸 뒤 내려간다.

---

# 22단계. 6주차①: README, 이력서 v3, 포트폴리오

## 22-1. 한 일

AWS에 올리기는 계정이 있어야 해서 미루고, 6주차 문서를 먼저 했다. 배포 URL과 EC2 측정값은 올린 뒤 채운다.

| 산출물 | 위치 | 쪽수 |
|---|---|---|
| README 다시 쓰기 | 저장소 `README.md` | — |
| 이력서 백엔드 v3 | `reports/career/resume/이경민-이력서-v3-백엔드.pdf`(원본 `backend.html`, v2는 `backend-v2.html`로 보관) | 2쪽 |
| 백엔드 포트폴리오 | `reports/career/portfolio/이경민-포트폴리오-백엔드.pdf`(원본 `backend.html`) | 1쪽 |

## 22-2. README: 읽는 사람이 30초 안에 볼 것부터

예전 README는 "풀려는 문제" 목록이었다. 계획이지 결과가 아니다. 새 README는 이 순서다.

1. **한 줄 소개와 한 줄 주장.** "문제를 테스트로 먼저 재현하고, 해결책은 측정해서 골랐다."
2. **측정한 것(표 한 장).** 동시 주문(현금 7,597,800원 오차), 락 비교(낙관적 락 57% 실패), 조회(166 → 1.1 ms), 캐시(DB 블록 5~9배 감소), 부하(잠금 대기 1,086초 → 5초), 테스트(155개, 라인 92.5%).
3. **해결한 문제 6개.** 각각 문제 → 한 일 → 고른 것 → 근거. 자세한 과정은 ROADMAP으로 보낸다.
4. 구조, API 표, 실행, 배포, **아직 하지 않은 것**, 문서, 기술.

왜 이렇게 했나:

- **숫자에는 조건을 붙였다.** "노트북 한 대에서 잰 값, 시간은 흔들린다"를 표 바로 아래에 적었다. 조건 없는 숫자는 면접에서 "어디서 쟀어요?" 한 마디에 무너진다.
- **흔들리지 않는 지표를 앞에 뒀다.** 응답 시간보다 DB 블록 수, 잠금 대기 시간, 500 개수. 기계 부하와 상관없이 같은 값이 나온다.
- **"아직 하지 않은 것"을 숨기지 않았다.** 다른 기계에서의 부하 측정, HTTPS, 무중단 배포. 한계를 먼저 말하면 나머지 숫자를 믿게 된다.
- **틀릴 뻔한 이야기를 넣었다.** GC를 바꿔서 빨라진 줄 알았는데 기계가 한가해진 것이었다(21-8). 측정을 의심하는 습관을 보여 주는 가장 짧은 증거다.

## 22-3. 이력서 v3: 무엇을 바꿨나

- **한 줄 소개:** "거래 로직을 서버 트랜잭션과 행 잠금으로 지키는" → "**동시성 문제를 테스트로 재현하고, 해결책은 측정해서 고르는**". 행 잠금은 누구나 쓴다. 차이는 "재현하고 비교해서 골랐다"는 과정이다.
- **프로젝트 맨 위에 모의투자 대회 서버.** 줄마다 "무엇을 → 어떻게 → 수치" 순서. 7줄.
- **예전 모의 주식거래 서비스(Next.js)는 3줄로 줄였다.** pg_cron·JWT·RLS·마이그레이션 61개는 기술 줄로 옮겼다. 같은 도메인 프로젝트가 두 개라, 새 것이 주인공이고 예전 것은 "같은 문제를 다른 스택에서도 풀었다"는 보조다.
- **INTRO에서 예전 서비스 문장을 뺐다.** 아래 프로젝트 칸과 겹치고, 3쪽으로 넘어가던 것을 2쪽에 맞췄다.
- **SKILLS:** Java·Spring Boot·JPA·PostgreSQL을 **주력** 맨 앞으로 올렸다(백엔드 지원용이고, 포트폴리오의 Strong과도 맞춘다). 사용 경험에는 Flyway, Testcontainers, k6를 넣었다.
- **넣지 않은 것:** 배포 URL(아직 없음), 가정 트래픽 2~3배 결과(재지 않음). 재지 않은 숫자는 쓰지 않는다.

## 22-4. 포트폴리오 1쪽: 게임용과 같은 틀

게임 포트폴리오(`game.html`)와 같은 CSS와 배치를 쓰고, 대표 프로젝트를 둘로 했다.

- **모의투자 대회 서버:** 이력서 7줄을 5줄로 줄였다(캐시와 조회를 한 줄로, 가격 엔진과 배포를 한 줄로).
- **Ember Sentinel:** 게임용은 앱·영상 쪽이 앞이었다. 백엔드용은 서버 고도화(인증·요청 제한·토큰 회전, 테스트 143개)를 앞으로 옮겼다.
- 한국어 줄바꿈이 낱말 가운데서 끊겨("4개\n로") `word-break: keep-all`을 넣었다.

## 결과

- README, 이력서 v3(2쪽), 포트폴리오(1쪽)를 만들었다. 쪽마다 이미지로 바꿔 넘침과 줄바꿈을 확인했다.
- 21단계 커밋(`1a2aece`)이 CI를 통과했고, CI가 처음으로 ghcr.io에 이미지를 올렸다.
- **저장소 공개는 프로젝트가 끝난 뒤에 한다.** 그때까지 이력서·포트폴리오의 GitHub 링크는 읽는 사람이 열 수 없으므로, 공개 전에 지원서를 낼 때는 링크 대신 PDF(README·ROADMAP)를 함께 낸다.

## 외울 것

- **Q. README 맨 위에 무엇을 두나?**
  → 한 줄 소개, 한 줄 주장, 측정 결과 표. 읽는 사람은 30초 안에 "무엇을 했고 얼마나 좋아졌나"를 본다. 계획 목록이 아니라 결과를 둔다.
- **Q. 노트북에서 잰 성능 숫자를 이력서에 써도 되나?**
  → 쓰되 조건을 붙이고, 흔들리지 않는 지표를 함께 쓴다. 응답 시간은 기계 부하에 따라 흔들리지만 DB 블록 수, 잠금 대기 시간, 오류 수는 그렇지 않다. 재지 않은 숫자(가정의 2~3배)는 쓰지 않는다.
- **Q. 이력서 한 줄은 어떤 순서로 쓰나?**
  → 무엇을(갱신 손실) → 어떻게(재현하고 락 3종 비교) → 결과(비관적 락 선택, 낙관적 락 57% 실패). 기술 이름만 나열하면 누구나 쓸 수 있는 줄이 된다.
- **Q. "아직 하지 않은 것"을 왜 README에 적나?**
  → 한계를 먼저 말해야 나머지 숫자를 믿는다. 면접에서 물어볼 곳을 내가 먼저 정하는 효과도 있다.
- **Q. 비슷한 프로젝트가 둘이면?**
  → 하나를 주인공으로 두고, 다른 하나는 줄여서 "다른 스택에서도 같은 문제를 풀었다"는 보조로 쓴다. 둘 다 길게 쓰면 같은 이야기를 두 번 하는 셈이다.

---

# 23단계. 6주차②: AWS에 실제로 올리기

## 23-1. 한 일

docs/05 순서대로 무료 플랜 계정에 올렸다. 계정·비밀값·콘솔 작업은 직접 했고, 막힐 때마다 로그와 화면을 보고 원인을 좁혔다.

| 순서 | 결과 |
|---|---|
| 무료 플랜 가입, 루트 MFA | 크레딧 $100, 남은 기간 182일 |
| 예산 알림 | `league-monthly` 월 $10. 2026-09-30 이후 추가한 수신자는 메일 인증을 해야 알림이 온다 |
| 보안 그룹 | `league-app`(80 전체, 22 내 IP), `league-db`(5432, 소스 = `league-app` 그룹) |
| RDS | PostgreSQL **16.15**, db.t4g.micro, 퍼블릭 액세스 아니요, 초기 DB `league` |
| EC2 | t3.micro(크레딧 standard), Amazon Linux 2023, 20GiB, 사용자 데이터로 Docker·compose·스왑 설치 |
| 배포 | `08535ea` 이미지. health `UP`, 토큰 없는 관리자 요청 401, 대회 1개 생성 |

새로 만든 것: `deploy/upload.sh`(파일 올리기), `scripts/load-test-remote.sh`(배포한 서버에 k6).

## 23-2. 리전: 서울이 아니라 시드니

무료 플랜 계정은 "프로젝트"에 리전이 하나 정해져 있었다. 서울(`ap-northeast-2`) 주소로 들어가자 "리전 사용 불가 … 다른 리전은 플랜을 업그레이드"가 떴다. 업그레이드하면 크레딧을 넘는 사용량이 청구되므로 **시드니에 그대로 두었다.**

- 대가: 한국에서 TCP 연결에 약 0.28초. 데모에는 지장 없다.
- 측정에는 영향이 크다. 통과 기준(p95 300ms)보다 왕복이 길다. 그래서 k6를 **같은 리전의 CloudShell**과 **노트북** 두 곳에서 돌려 나눠 읽기로 했다(23-5).
- 새 콘솔에는 리전 메뉴가 없었다. 지금 리전은 주소창의 `region=`으로 확인했다.

## 23-3. 막혔던 곳 세 가지

**① 앱이 DB에 못 붙었다(`Connect timed out`).**

```
헬스 체크 실패: 180초 동안 healthy 가 아니었습니다(마지막 상태: starting)
Caused by: java.net.SocketTimeoutException: Connect timed out
되돌릴 이미지가 없어 앱을 멈춥니다
```

- 예외 종류로 원인을 좁혔다. 비밀번호가 틀리면 `password authentication failed`, 주소가 틀리면 `UnknownHostException`이다. **연결 시간 초과는 패킷이 버려진 것**이라 네트워크(보안 그룹)를 봤다.
- RDS의 보안 그룹이 `default` 하나뿐이었다. 만들 때 미리 골라져 있던 것을 빼지 않았다. `default`는 같은 그룹끼리만 받으므로 `league-app`이 붙은 EC2의 연결이 버려졌다.
- RDS 수정에서 `league-db`를 넣고 `default`를 뺐다. 보안 그룹 변경은 재시작 없이 1~2분이면 적용된다. 같은 명령으로 다시 배포하자 성공했다.
- `deploy.sh`는 21단계에서 만든 대로 동작했다. 처음 배포라 되돌릴 이미지가 없으니 **앱을 멈췄다**(죽었다 살아나기를 반복하지 않음).

**② 관리자 API가 계속 401이었다.**

- 토큰을 화면에 띄우지 않고 비교하려고 **길이와 해시 앞 8자리**만 보기로 했다. 길이가 40이었다. 관리자 토큰(`openssl rand -hex 32`)은 64자이고, 40자는 GitHub classic 토큰(`ghp_` + 36자)이다. ghcr.io 로그인에 쓴 토큰을 관리자 토큰 자리에 붙여 넣은 것이었다.
- 토큰이 두 개라는 것을 docs/05에 표로 적었다.

**③ 긴 명령을 붙여 넣으면 줄이 잘렸다.**

- `scp … deploy/deploy.sh ec2-user@…:/opt/league/`가 두 줄로 잘리면서, 뒷줄 `deploy/deploy.sh ec2-user@…`가 **노트북에서** 배포 스크립트로 실행됐다.
- 아무 일도 일어나지 않았다. `deploy.sh`는 맨 먼저 이미지를 받는데, `ec2-user@…`는 이미지 이름이 아니라 거기서 멈췄다. "받기에 실패하면 지금 도는 것은 건드리지 않는다"는 순서가 엉뚱한 곳에서 실행돼도 안전하게 만들었다.
- 그 뒤로 여러 단계는 스크립트 하나로 묶었다(`deploy/upload.sh <IP>`). 여러 줄 명령은 줄 끝에 `\`를 붙여 쓴다.

## 23-4. 비밀값은 어디에 두었나

| 값 | 어디에 | 하지 않은 것 |
|---|---|---|
| RDS 비밀번호, 관리자 토큰 | 서버 `/opt/league/.env`(600)와 비밀번호 관리자 | 채팅·커밋·노트북 파일에 남기기 |
| GitHub 토큰(`read:packages`만, 90일) | 서버의 docker 로그인 정보 | 쓰기 권한(`write:packages`, `repo`) 주기 |
| 관리자 토큰을 노트북에서 쓸 때 | `read -s TOKEN`(화면에 안 보임) → 셸 변수 | 명령줄에 직접 쓰기(셸 기록에 남는다) |
| SSH 키 | `~/.ssh/league.pem`(400) | 다른 곳에 사본 두기 |

## 23-5. 배포한 서버에 k6: 러너를 따로 만든 이유

`scripts/load-test.sh`(21단계)는 로컬에 앱 컨테이너를 띄우고 시계를 고정한다. 배포한 서버는 그럴 수 없다.

- **장 시간 확인.** 운영 서버는 실제 시계다. 한국 평일 09:05~15:00가 아니면 러너가 시작하지 않는다(측정 중 장이 닫히지 않게 앞뒤로 여유).
- **오늘 시작하는 대회.** `STARTS_ON`을 오늘로 주면 대회를 시작할 때 오늘 가격이 만들어진다(`ContestService.start`).
- **왕복 시간 먼저.** 응답 시간에서 빼고 읽으려고 5번 잰다.
- **서버 지표 전후.** `http.server.requests`, 커넥션 대기, GC를 측정 전후로 남긴다. 서버 지표는 평소 닫혀 있으니 측정할 때만 연다.
- 노트북(macOS)과 CloudShell(Linux) 양쪽에서 돈다. `date` 옵션이 달라 둘 다 처리했다.

## 결과

- 첫 배포 성공: `http://<EC2 IP>/actuator/health` → `{"status":"UP"}`, Swagger 302, 토큰 없는 관리자 요청 401, 대회 생성 200.
- 측정은 아직이다. 금요일 저녁이라 장이 닫혀 있었다. 다음 평일 장 시간에 한다(24단계).

## 외울 것

- **Q. `Connect timed out`과 `Connection refused`는 어떻게 다른가?**
  → refused는 상대가 "그 포트에는 아무도 없다"고 즉시 거절한 것이다. timed out은 응답이 아예 없다. 클라우드에서 timed out은 대개 보안 그룹·방화벽이 패킷을 조용히 버린 것이다.
- **Q. DB 보안 그룹의 소스를 왜 IP가 아니라 보안 그룹으로 지정하나?**
  → EC2를 새로 만들거나 껐다 켜면 IP가 바뀐다. 그룹으로 지정하면 "이 그룹이 붙은 서버"를 허용하므로 IP와 상관없이 맞는다.
- **Q. RDS를 왜 퍼블릭 액세스 "아니요"로 만드나?**
  → DB 포트가 인터넷에 열리면 비밀번호 무작위 대입 대상이 된다. 앱 서버만 같은 VPC 안에서 붙으면 된다.
- **Q. 첫 배포가 실패했을 때 앱을 멈춘 것이 왜 맞나?**
  → `restart: unless-stopped`라 두면 죽었다 살아나기를 끝없이 반복한다. 로그가 쌓이고 DB에 연결 시도가 몰린다. 되돌릴 이미지가 없으면 멈추고 사람이 보게 하는 편이 낫다.
- **Q. 비밀값을 화면에 띄우지 않고 두 값이 같은지 어떻게 확인하나?**
  → 길이와 해시(SHA-256) 앞 몇 자리를 비교한다. 해시 일부로는 원래 값을 알 수 없다.
- **Q. 부하 발생기를 어디에 두어야 하나?**
  → 서버 처리 능력을 재려면 네트워크가 짧은 곳(같은 리전)에 둔다. 사용자 체감을 보려면 사용자 위치에서 잰다. 둘을 섞으면 왕복 시간이 서버가 느린 것처럼 보인다.
- **Q. 무료 플랜인데 왜 서울을 못 썼나?**
  → 무료 플랜은 리전이 하나로 고정된다. 다른 리전을 쓰려면 유료 플랜이 필요하고, 그러면 크레딧을 넘는 사용량이 청구된다. 비용 보호를 지키려고 시드니에 남았다.

---

# 24단계. 6주차③: 시나리오를 늘리고, 그래프로 보게 만들기

## 24-1. 왜 이 단계를 했나

23단계까지는 k6가 끝에 표 하나를 남겼다. p95가 몇 ms, 실패율이 몇 %인지는 알 수 있지만 **왜** 그런지는 알 수 없었다.
"측정해서 고른다"를 보여주려면 (1) 문제가 날 만한 상황을 여러 개 재현하고, (2) 그때 서버 안에서 무슨 일이 있었는지 시간 순으로 보고,
(3) 응답이 성공했어도 데이터가 맞는지 확인할 수 있어야 한다. 이 단계는 그 세 가지 도구를 만들었다. 문제를 실제로 고치는 것은 25단계부터다.

## 24-2. 한 일

| 무엇 | 파일 | 하는 일 |
|---|---|---|
| 시나리오 목록 | `docs/06-부하-시나리오와-관측.md` | 정상 4개(N1~N4), 문제 유발 11개(P1~P11). 각각 "실제 상황"과 "의심하는 원인" |
| 시나리오 5개 | `scripts/k6/realistic.js`, `lib.js` | `steady`(기준선), `journey`(사용 흐름), `hot`(연타·재전송), `rush`(장 시작 몰림), `tick`(틱 경계 몰림) |
| 불변식 검사 API | `contest/InvariantAuditService.java`, `GET /api/admin/contests/{id}/invariants` | 돈과 주식이 장부와 맞는지 6가지를 SQL 로 대조. k6 가 끝날 때 자동으로 부른다 |
| Prometheus 지표 | `build.gradle`, `application.yml` | `/actuator/prometheus`. 응답 시간·커넥션 얻기·사용 시간을 히스토그램으로 |
| Grafana 대시보드 | `monitoring/` | 노트북 Docker 에 Prometheus + Grafana. 패널 18개 + 점검표. AWS 비용 없음 |
| k6 HTML 리포트 | 두 러너 | 측정마다 `report.html`. 끝에 그 측정 시간대의 Grafana 링크를 출력 |

## 24-3. 원칙: 버그를 심지 않는다

문제 시나리오는 "일부러 망가뜨린 코드"가 아니라 **지금 코드에서 약할 만한 곳**을 겨냥했다. 코드를 읽고 고른 근거:

- **P1 `hot`**: 주문은 계좌 행을 `SELECT ... FOR UPDATE`로 잠근다(14단계). 같은 계좌로 주문이 몰리면 뒤 요청은 앞 트랜잭션이 끝날 때까지 기다리는데, **기다리는 동안 DB 커넥션을 쥐고 있다**. 풀은 10개다.
- **P3 `tick`**: 조회 캐시 키에 틱 번호가 들어 있다(19단계). 새 틱이 되면 모든 키가 한꺼번에 바뀐다.
- **P4 `settle-storm`**: 지정가 정산 배치는 대기 주문을 한 건씩 처리한다(`LimitOrderSettlementService`). 수천 건이면 1분 주기 안에 끝나지 않을 수 있다.
- **P5 `cancel-race`**: 취소와 체결이 같은 주문을 동시에 건드리면? 상태 전이를 조건부 UPDATE 로 막았지만(I10), 부하에서 확인한 적은 없다.

문제가 안 나오면 그것도 결과다. "버텼다"와 그 근거 그래프를 남긴다.

## 24-4. 불변식 검사: 200 이었다고 맞는 것은 아니다

동시성 버그는 대개 HTTP 상태로 보이지 않는다. 같은 돈을 두 번 써도 두 요청 모두 200 이다. 그래서 부하가 끝나면 장부와 대조한다.

| 코드 | 규칙 |
|---|---|
| `I5_CASH_EQUALS_LEDGER` | 현금 + 묶인 현금 = 원장 합계 |
| `RESERVED_CASH_EQUALS_PENDING_BUYS` | 묶인 현금 = 대기 중인 지정가 매수의 (지정가 × 수량 + 수수료) 합 |
| `RESERVED_QTY_EQUALS_PENDING_SELLS` | 묶인 주식 = 대기 중인 지정가 매도 수량 합 |
| `HOLDING_EQUALS_FILLED_NET` | 보유 수량 = 체결된 매수 − 체결된 매도 |
| `ORDER_LEDGER_ONCE` | 체결된 주문은 원장에 거래 1줄(+수수료 1줄), 체결 안 된 주문은 0줄 |
| `I10_FILL_FIELDS_MATCH_STATUS` | 체결 정보는 FILLED 주문에만, 빠짐없이 |

```java
// 모든 검사가 같은 순간을 보도록 스냅샷 격리 + 읽기 전용
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public Report audit(Long contestId) { ... }
```

- **왜 `REPEATABLE_READ`인가**: 트래픽 중에 돌리면 검사 A 와 검사 B 사이에 주문이 하나 체결될 수 있다. 그러면 각 검사는 맞는데 서로 다른 시점을 비교해 거짓 경보가 난다. 트랜잭션 전체가 한 스냅샷을 보게 했다.
- **검사를 검사했다**: "늘 0을 돌려주는 검사"도 통과로 보인다. 그래서 테스트는 정상 거래 뒤 0건을 확인하고, 장부를 한 곳씩 일부러 망가뜨려 그 검사가 잡는지도 본다(6개).
- **실제로 걸린 것**: 처음 쓴 I10 규칙은 "FILLED 이면 체결 정보가 모두 있다"의 반대 방향을 `(가격 and 수수료 and 틱) is not null`로 썼다. 취소된 주문에 가격만 들어가도 못 잡았다. 망가뜨리는 테스트가 실패해서 알았다. "모두 있다"의 부정은 "하나라도 있다"다.

## 24-5. 그래프: 무엇을 어디서 보나

| 무엇 | 어디서 | 보여주는 것 |
|---|---|---|
| k6 HTML 리포트 | 결과 폴더의 `report.html` | 사용자 쪽: 보낸 요청, 응답 시간, 실패를 시간 순으로 |
| Grafana | 노트북 `localhost:3000` | 서버 안: API 별 p95, 응답 상태(409·503·500), 커넥션 풀(사용 중·대기·얻는 시간·쥐고 있던 시간), CPU, 톰캣 스레드, 힙·GC, 주문 재시도, 캐시 |
| CloudWatch | AWS 콘솔 | 기계: EC2 CPU·크레딧, RDS CPU·연결 수 |

- **왜 히스토그램인가**: Micrometer 는 기본으로 평균·최대만 낸다. 평균은 일부 요청의 막힘을 숨긴다. 구간별 개수(히스토그램)를 내야 Prometheus 가 "그 10초 동안의 p95"를 계산할 수 있다. 커넥션을 **얻는 시간**과 **쥐고 있던 시간**도 히스토그램으로 켰다. 둘을 나눠 봐야 "풀이 작다"와 "누가 오래 쥔다"가 구분된다.
- **왜 노트북에 띄우나**: t3.micro(메모리 1GB)에 Prometheus·Grafana 를 같이 올리면 측정 대상의 자원을 뺏는다. 노트북이 5초마다 서버의 `/actuator/prometheus`를 가져온다. 비용도 0.
- **점검표**를 대시보드 맨 위에 두었다: ① p95 가 오른 구간 찾기 → ② 커넥션 대기 → ③ CPU → ④ 503·5xx 시작 시각 → ⑤ 회복 시간.

## 24-6. 첫 측정: 그래프가 "아직 판단하지 말라"고 했다

로컬에서 `steady`(기준선)와 `hot`(P1)을 돌렸다.

| | steady (1배, 2분) | hot (1배, 1분) |
|---|---|---|
| 503 | 4,293 | 278 |
| 500 류 | 0 | 0 |
| 같은 키 → 다른 주문 | - | 0 |
| 불변식 위반 | 0 | 0 |
| 맥 load average | **64 → 258** | 40 |

Grafana 에서 `hot` 구간을 보면 P1 가설과 맞는 모습이 나왔다: 커넥션 사용 중 10/10, 대기 최대 104, 커넥션을 쥐고 있던 시간 p95 0.2초 → 1.5초, 그 뒤 503 이 초당 10건까지.

**그런데 같은 시각 앱 CPU 도 100% 였다.** 점검표 ③에 걸린다. 이 맥은 Unity 에디터와 다른 Docker 컨테이너가 같이 돌며 load average 가 200 을 넘었다. 기준선인 `steady`조차 p95 가 35초였다. 이 상태에서는 "잠금 대기가 커넥션을 쥐어서"인지 "CPU 가 모자라 모든 게 느려서"인지 구분할 수 없다. 그래서 **결론을 내지 않고 EC2 에서 다시 잰다**(25단계).

이번 측정에서 남은 것: 도구가 끝까지 동작한다(리포트, 그래프, 불변식 검사). 부하 속에서도 돈과 주식은 장부와 맞았다. 500 은 0 건이었다.

## 24-7. 겪은 문제: Prometheus 가 뜨자마자 죽었다

`monitoring/up.sh`는 대상(로컬·EC2)을 바꾸면 Prometheus 에 HUP 신호를 보내 설정을 다시 읽게 했다. 처음 띄울 때도 보냈더니, 신호 처리기가 등록되기 전이라 **기본 동작(종료)** 으로 죽었다(exit 2, 로그 없음). 그 사이 측정 하나는 그래프가 비었다. 이미 떠 있을 때만 다시 시작하도록 고쳤다.

## 결과

- 시나리오 5개(`steady`·`journey`·`hot`·`rush`·`tick`) + 목록 15개, 불변식 검사 6개(테스트 6개 통과), Grafana 대시보드, k6 HTML 리포트
- 로컬 첫 측정: 500 0건, 불변식 위반 0건, 같은 키 중복 주문 0건. 지연 수치는 맥 과부하로 쓰지 않는다

## 외울 것

- **Q. 부하 테스트에서 응답이 모두 성공이면 데이터도 맞다고 할 수 있나?**
  → 아니다. 같은 돈을 두 번 쓰는 동시성 버그는 두 요청 모두 200 을 돌려준다. 끝난 뒤 잔액·보유·원장을 서로 대조해야 한다.
- **Q. 불변식 검사를 트래픽 중에 돌려도 되나?**
  → 검사 전체를 한 트랜잭션(REPEATABLE READ)으로 묶으면 모든 쿼리가 같은 스냅샷을 본다. 검사 사이에 체결이 끼어들어 생기는 거짓 경보가 없다.
- **Q. 응답 시간을 평균이 아니라 p95·p99 로 보는 이유는?**
  → 평균은 소수의 매우 느린 요청을 숨긴다. 잠금 대기처럼 일부 요청만 막히는 문제는 p50 은 멀쩡하고 p99 만 튄다.
- **Q. 커넥션 "얻는 시간"과 "쥐고 있던 시간"을 왜 나눠 보나?**
  → 얻는 시간만 길면 풀이 작은 것이다. 쥐고 있던 시간도 길면 트랜잭션 안에서 무언가를 기다리는 것이다(잠금, 느린 쿼리). 해결책이 다르다: 풀 크기 vs 트랜잭션 안의 대기 줄이기.
- **Q. 그래프에서 원인처럼 보이는 것이 나왔는데 왜 결론을 미뤘나?**
  → 같은 시각 CPU 가 100% 였다. 원인 후보가 둘(잠금 대기, CPU 부족)인데 이 측정으로는 가를 수 없다. 측정 환경(과부하 맥)이 문제이므로, 자원이 일정한 곳에서 다시 잰다.
- **Q. 모니터링을 왜 서버가 아니라 노트북에 띄웠나?**
  → 측정 대상과 같은 기계에 두면 자원을 나눠 써서 측정을 흔든다. t3.micro 는 메모리가 1GB 라 여유도 없다.
- **Q. "모두 있다"를 SQL 로 검사할 때 실수하기 쉬운 점은?**
  → `(A and B and C) <> 상태` 식으로 묶으면 "일부만 있음"을 놓친다. 상태별로 "하나라도 없다"와 "하나라도 있다"를 따로 쓴다. 이번에 망가뜨리는 테스트로 잡았다.

---

# 25단계. 6주차④: 서버에서 재고, 원인을 좁히고, 고치고, 다시 재기

## 25-1. 한 일

| 무엇 | 파일 | 결과 |
|---|---|---|
| EC2 기준선(steady) | CloudShell → EC2 | 주문 p95 23.7 ms, 조회 p95 7.4 ms, 실패 0. CPU 15% 안팎, 커넥션 대기 0 |
| P1 `hot`(계좌 5개) | 같은 조건 | 503 0. **로컬에서 본 503 은 맥 CPU 부족 탓**이었다 |
| P1 `hot`(한 계좌, 초당 100건) | `HOT_USERS=1` | p99 344~757 ms, 커넥션 10/10, 대기 22. CPU 12~16% |
| 매분 튐 추적 | `deploy/psql.sh`, `deploy/sql/*.sql`, `vmstat` | 가설 4개 중 3개 기각, **하이퍼바이저 CPU steal(매분 0~2초)** 확인 |
| 해결: 사용자별 주문 속도 제한 | `order/OrderRateLimiter` 외 2개 | 초당 5건·최대 10건, 넘으면 커넥션 잡기 전에 429 |
| 재측정 | 같은 조건 | 대기 22 → 0, 정각 잠금 대기 9 → 0, 받아들인 주문 p99 126 ms |
| 측정 보고서 | `docs/07-측정-보고서.md`, `monitoring/capture.mjs` | 시나리오별 그래프 캡처 + 본 것 → 판단 |

## 25-2. 그래프는 "결론을 미뤄라"와 "결론을 내라"를 둘 다 말한다

같은 모양의 커넥션 대기 그래프가 두 번 나왔다.

| | 로컬(24단계) | EC2 한 계좌(이번) |
|---|---|---|
| 커넥션 대기 | 최대 104 | 최대 22 |
| **CPU** | **100% 에 붙어 있었다(대기보다 50초 먼저)** | **12~16%** |
| 판단 | CPU 부족 → 모두 느려짐 → 커넥션을 오래 쥠 | 잠금 줄 → 커넥션을 오래 쥠 |

대기 그래프만 보면 둘은 구별되지 않는다. **CPU 그래프가 가른다.** 점검표가 "CPU 를 먼저 지운다"인 이유다.

## 25-3. 매분 튐 추적: 가설을 하나씩 지웠다

튐은 측정을 언제 시작했든 **실제 시계로 매분 58초~3초**에 나왔다. 늘어난 것은 "커넥션을 쥐고 있던 시간"(트랜잭션 안의 시간)이었다.

| 가설 | 확인 방법 | 결과 |
|---|---|---|
| PostgreSQL autovacuum(로컬은 1분 주기) | `./psql.sh -f /sql/autovacuum.sql` | **기각.** RDS 는 15초 주기이고 돈 시각이 맞지 않았다 |
| DB 가 그 순간 바빴다 | `activity.sql`: 1초마다 세션 대기 종류를 셈 | **기각.** 정각에 세션 9개가 행 잠금을 기다렸지만 디스크·내부 잠금·autovacuum·RDS 관리 접속은 0. 잠금을 쥔 쪽은 DB 안에서 아무것도 기다리지 않았다 → 앱 쪽이 멈췄다 |
| EC2 의 1분 주기 예약 작업 | `systemctl list-timers`, `journalctl` | **기각.** `refresh-policy-routes` 가 1분 주기였지만 17:13:33, 17:15:06 에 돌았다 |
| 하이퍼바이저가 CPU 를 가져간다 | `vmstat -t 1` 의 `st` 열 | **확인.** 매분 0~2초에만 부하 중 16~22%, 쉴 때 1~4%. 그 밖은 0 |

```sql
-- deploy/sql/activity.sql (요약): 1초마다 "누가 무엇을 기다리나"를 한 줄로
select to_char(now() at time zone 'Asia/Seoul', 'HH24:MI:SS') as 시각,
       count(*) filter (where wait_event_type = 'Lock') as 잠금, ...
from pg_stat_activity where backend_type in ('client backend', 'autovacuum worker')
\watch i=1 c=150
```

- **왜 `psql.sh`로 감쌌나**: RDS 는 인터넷에서 접근할 수 없다(23단계). 같은 VPC 의 EC2 에서 붙어야 한다. `.env`를 컨테이너 환경 변수로만 넘겨 비밀번호가 화면·셸 기록에 남지 않게 했다.
- **왜 로컬 결과로 끝내지 않았나**: 로컬 DB 는 autovacuum 이 정확히 매분 돌아 그럴듯했다. 운영 DB 는 설정이 달랐다(`autovacuum_naptime` 15s). 운영 값은 운영에서 확인한다.
- **"Client:ClientRead"가 뜻하는 것**: DB 가 클라이언트의 다음 SQL 을 기다리는 상태다. 잠금을 쥔 트랜잭션이 이 상태라면, 늦은 쪽은 DB 가 아니라 앱(또는 앱과 DB 사이)이다.

## 25-4. 해결: 멈춤은 못 막으니 번짐을 끊는다

CPU 를 잠깐 빼앗기는 일은 우리 코드 밖이다. 더 비싼 인스턴스로 가도 GC·네트워크처럼 짧은 멈춤은 생긴다.
문제는 그 멈춤이 **한 계좌 잠금 줄을 따라 커넥션 9개로 번진다**는 점이다. 줄이 길어질 수 없게 했다.

```java
// order/OrderRateLimiter: 사용자마다 통에 토큰 최대 burst 개, 초당 perSecond 개씩 다시 찬다
synchronized boolean tryTake(long now, double perSecond, double burst) {
  tokens = Math.min(burst, tokens + (now - last) / 1e9 * perSecond);  // 지난 시간만큼 채우되 burst 를 넘지 않게
  last = now;
  if (tokens < 1) {
    return false;          // 통이 비었다 → 거절
  }
  tokens -= 1;
  return true;
}
```

```java
// order/OrderRateLimitInterceptor: 컨트롤러·트랜잭션보다 먼저 돈다
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
  if (!"POST".equals(request.getMethod())) return true;     // 주문 접수만. 조회·취소는 막지 않는다
  Long userId = parse(request.getHeader(USER_HEADER));
  if (userId == null || limiter.tryAcquire(userId)) return true;  // 헤더가 없으면 컨트롤러가 400 으로 답한다
  rejected.increment();                                    // orders.rate_limited 지표
  throw new BusinessException(ErrorCode.RATE_LIMITED);     // 429 + Retry-After: 1
}
```

- **왜 인터셉터인가**: 서비스 안에서 거절하면 이미 트랜잭션이 열려 커넥션을 쥔 뒤다. 인터셉터는 그 전에 돈다. 거절된 요청은 1~2 ms 만에 돌아갔다.
- **왜 사용자별 `synchronized`인가**: 통 하나를 같은 사용자의 동시 요청끼리만 다툰다. 사용자 전체에 잠금 하나를 쓰면 속도 제한이 새 병목이 된다.
- **왜 토큰 버킷인가**: "1초에 5건"을 고정 창으로 세면 0.99초와 1.01초에 5건씩, 순간 10건이 통과한다. 토큰 버킷은 순간 허용량(burst)과 평균 속도를 따로 정한다. 연타·재전송 몇 건은 burst 로 받아 준다.
- **오래 쉰 사용자 기록 지우기**: 1만 번 호출마다 60초 넘게 주문이 없던 사용자를 지운다. 안 지우면 사용자 수만큼 메모리가 계속 는다.
- **왜 기본값이 꺼짐인가**: 운영(`application-prod.yml`)에서만 켠다. 동시성 테스트는 한 사용자로 주문을 한꺼번에 보내 잠금을 시험하므로, 켜 두면 그 테스트가 잠금이 아니라 속도 제한을 시험하게 된다.
- **고르지 않은 것**: 잠금 대기 시간 제한(`lock_timeout`)은 줄이 생긴 뒤에 끊는다. 앱 안에서 계좌별로 줄 세우기는 서버 한 대에서만 맞다. Kafka 같은 큐는 응답 방식(202 접수)부터 바뀌고 t3.micro 메모리(1GB)로는 띄울 수 없다.
- **한 번에 하나만 바꿨다**: 둘을 같이 넣으면 재측정에서 무엇이 효과를 냈는지 가를 수 없다.
- **한계**: 기록이 서버 메모리에 있다. 서버가 여러 대면 각자 세므로 한도가 대수만큼 커진다. 그때는 Redis 같은 공용 저장소로 옮긴다.

## 25-5. 재측정

| | 고치기 전 | 고친 뒤 |
|---|---|---|
| 커넥션 대기 최대 | 22 ~ 30 | **0** |
| 정각의 잠금 대기 세션 | 9 | **0** |
| 받아들인 주문 p99 | 344 ~ 757 ms (거절 포함) | **126 ms** (서버 측정) |
| 429 | - | 약 95% |
| steady: 429 · 실패 | - | 0 · 0 |
| 불변식 위반 · 같은 키 중복 | 0 · 0 | 0 · 0 |

- **남은 것**: 매분 정각에 트랜잭션 하나하나가 늦어지는 일(커넥션 쥔 시간 p99 240~335 ms)은 그대로다. 줄이 없으니 번지지 않을 뿐이다.
- **steady 의 p99 가 91 → 192 ms 로 나빠진 것**: 50 ms 를 넘은 곳은 03:30:00, 03:30:05, 03:31:00, 모두 정각이다. 속도 제한은 한 번도 거절하지 않았다. 그 순간 빼앗긴 CPU 양이 실행마다 달라서로 본다. 모든 요청의 p99 를 100 ms 아래로 묶어야 한다면 CPU 를 나눠 쓰지 않는 인스턴스(m·c 계열)의 비용과 비교해 정한다. 목표(p95 300 ms)는 넉넉히 만족한다.

## 25-6. 측정 보고서와 캡처

- `docs/07-측정-보고서.md`: 패널 사전(그래프마다 무엇을 보나), 시나리오별 그래프 + 본 것 → 판단, 질문과 답, 매분 튐 추적, 재측정. PDF 로도 만든다.
- `node monitoring/capture.mjs <이름> <시작초> <끝초> [패널 id]`: 헤드리스 Chrome 을 직접 조종해(Node 기본 WebSocket, 설치할 것 없음) 그래프가 다 그려질 때까지 기다린 뒤 PNG 로 찍는다. `chrome --screenshot`은 "Loading" 화면을 찍어 버렸다.
- 끝나면 Chrome 도우미 프로세스까지 이번 실행 전용 프로필 경로로 찾아 죽인다. 본체만 죽였더니 렌더러가 남았다.
- Chrome 이 업데이트를 받은 뒤 재시작 전에는 디버깅 포트를 열지 않았다. 못 뜨면 30초 기다린 뒤 Chrome 로그 끝을 보여 주게 했다.
- 지연 패널에서는 측정 준비 요청(가입·참가·관리자 API)을 뺐다. 맨 앞에 큰 막대로 섞여 주문 API 선을 가렸다.

## 결과

- EC2 기준선, P1 원인 확정(잠금 줄 + 매분 CPU steal), 사용자별 속도 제한, 재측정(대기 22 → 0)
- 테스트 8개 추가(토큰 버킷 6, HTTP 2). 진단 도구 `deploy/psql.sh` + SQL 2개
- 측정 보고서 `docs/07` (PDF)

## 외울 것

- **Q. 커넥션 풀 대기가 쌓였다. 풀을 늘리면 되나?**
  → 먼저 "얻는 시간"과 "쥐고 있던 시간"을 나눠 본다. 쥔 시간이 길어진 것이 먼저라면 트랜잭션 안에서 무언가를 기다리는 것이다(이번에는 한 계좌 잠금 줄). 풀을 늘리면 줄에 설 자리만 늘어난다.
- **Q. 같은 대기 그래프가 두 번 나왔는데 원인이 달랐다. 무엇으로 갈랐나?**
  → CPU. 로컬은 CPU 100% 가 먼저였고(모두 느려짐), EC2 는 CPU 12~16% 였다(잠금 줄).
- **Q. `vmstat`의 `st`는 무엇인가?**
  → steal. 가상 서버가 CPU 를 쓰려 했는데 하이퍼바이저가 그 시간을 다른 데 준 비율. t3 같은 버스트형은 CPU 를 다른 서버와 나눠 쓴다.
- **Q. `pg_stat_activity`에서 잠금을 기다리는 세션이 많은데 잠금을 쥔 세션은 `Client:ClientRead`였다. 무슨 뜻인가?**
  → DB 는 다음 SQL 을 기다리고 있었다. 늦은 쪽은 DB 가 아니라 앱(또는 네트워크)이다.
- **Q. 속도 제한을 왜 서비스가 아니라 인터셉터에 넣었나?**
  → 서비스 메서드는 트랜잭션 안이라 이미 커넥션을 쥐고 있다. 인터셉터는 그 전에 돌아, 거절된 요청이 커넥션을 쓰지 않는다.
- **Q. 고정 창 대신 토큰 버킷을 쓴 이유는?**
  → 고정 창은 창 경계 양쪽에서 한도의 두 배가 순간 통과한다. 토큰 버킷은 순간 허용량과 평균 속도를 따로 정한다.
- **Q. 서버가 두 대가 되면 이 속도 제한은?**
  → 각자 메모리에서 세므로 한도가 두 배가 된다. Redis 같은 공용 저장소에서 세야 한다.
- **Q. Kafka 를 쓰지 않은 이유는?**
  → 응답이 "접수(202)"로 바뀌어 멱등성·앱·테스트가 모두 바뀐다. t3.micro 메모리로는 띄울 수 없다. 측정으로 병목이 "사람이 낼 수 없는 속도"에서만 생긴다는 것을 확인했으므로, 속도 제한으로 충분했다.
- **Q. 고친 뒤 steady 의 p99 가 나빠졌다. 속도 제한 탓인가?**
  → 거절이 0건이었고, 튄 시각이 모두 매분 정각(CPU steal 자리)이었다. 실행마다 빼앗긴 양이 다른 것으로 본다. 확인한 근거와 확인하지 못한 부분을 나눠 말한다.

---

# 26단계. 캐시 스탬피드(P3) 측정 준비 (6주차⑤)

## 한 일

- **가속 시계.** `LEAGUE_CLOCK_SPEED=5`를 `LEAGUE_CLOCK_FIXED`와 함께 주면 시계가 그 시각부터 5배속으로 흐른다. 틱(5분)이 실제 60초마다 바뀐다. 시계를 고정해 두면 틱이 바뀌지 않아 캐시 키도 안 바뀌므로, 틱 경계 스탬피드를 재현할 수 없었다.
- **틱 경계를 매분 30초에.** 매분 0초 무렵의 CPU steal(25단계)과 겹치지 않게 반 분 비켰다. k6 `tick` 시나리오도 30초에 맞춰 시작하고, 폭주 1초 뒤 순위 스냅샷을 찍는다(스케줄러 대신). 그래서 가격·순위 캐시 키가 폭주 중에 바뀐다.
- **캐시 지표 2개.** `league.cache.load`(놓친 한 번의 DB 읽기 시간), `league.cache.concurrent_loads`(같은 키를 동시에 읽던 수의 최대). Grafana에 ④ 캐시 스탬피드 줄(놓침 수 10초 합, 동시 읽기 최대, 읽기 시간)을 더했다.
- **single-flight(설정으로 켬, 기본 꺼짐).** `LEAGUE_CACHE_SINGLE_FLIGHT=true`면 같은 키를 동시에 놓친 요청 중 첫 사람만 DB를 읽고, 나머지는 그 결과를 기다린다(최대 2초, 실패·초과면 직접 읽음). 같은 이미지에서 끄고 켜며 전후를 잰다.
- **로컬 확인(수치는 참고만).** 맥 load average 30~99(다른 앱)라 지연은 무의미. 다만 틱이 13→15→16→17로 실제로 넘어갔고, 새 스냅샷이 찍혔고, 가격 캐시를 같은 순간 최대 4명이 따로 읽었다(순위 2명). 스탬피드가 실제로 생긴다는 것까지만 확인.
- 테스트: `TimeConfigTest`(3), `ReadCacheStampedeTest`(4: 끈 상태는 8명 모두 읽음, 켠 상태는 1명만 읽고 7명은 결과를 받음, 앞사람 실패 시 직접 읽음). 전체 180개 통과.

## 왜

- 고치기 전에 **문제가 정말 있는지, 얼마나 큰지**부터 잰다. 1,000명 규모에서는 놓친 한 번의 DB 읽기가 짧아 동시 읽기가 몇 명에 그칠 수도 있다. 그러면 "고칠 필요가 크지 않다"가 결론이다. 그것도 측정으로 내린 판단이다.
- 전후를 같은 이미지·같은 서버에서 설정만 바꿔 재야 차이가 single-flight 때문이라고 말할 수 있다.

## 외울 것

- **Q. 캐시 스탬피드가 뭔가?** 캐시 키가 바뀌거나 만료되는 순간, 동시에 들어온 요청들이 모두 캐시를 놓쳐 같은 데이터를 DB에서 각자 읽는 것. 이 서버는 키에 버전(틱, 스냅샷 id)을 넣어 무효화를 없앴는데, 그 대가로 틱 경계마다 키가 한꺼번에 바뀐다.
- **Q. 동시에 놓치는 수는 얼마나 되나?** 대략 초당 요청 수 × 한 번 읽는 시간. 초당 200건에 읽기 20 ms면 4명쯤. 읽기가 느려질수록(DB가 바쁠수록) 커지고, 커지면 DB가 더 바빠지는 악순환이 된다.
- **Q. single-flight는?** 같은 키를 읽는 중이면 새로 읽지 않고 그 결과를 기다린다. 서버 메모리에서만 묶으므로 서버가 여러 대면 대수만큼 읽는다(그래도 요청 수보다 훨씬 적다). 앞사람이 멈추면 같이 멈추지 않게 기다리는 시간에 한도를 둔다.
- **Q. 다른 방법은?** 미리 채우기(틱이 바뀌기 직전에 다음 틱 값을 계산해 넣기), 만료 시각 흩뿌리기(TTL에 무작위 더하기, 이 서버처럼 버전 키에는 해당 없음), 오래된 값 잠깐 주기(stale-while-revalidate).

---

# 27단계. 다음 할 일

- **P3 서버 측정.** 같은 이미지로 single-flight 끈 채 `tick` 한 번 → 켜고 한 번. 동시 읽기 최대·놓침 수·폭주 구간 p95를 비교한다.

- **다음 시나리오**: P6 `snapshot-load`(참가자 수만 명) → P4·P5·P8(정산 배치·취소 경쟁·재전송, 정산 수동 실행 API 필요) → P2·P7·N4 → P9·P10·P11·N3.
- **측정이 끝나면** 서버 `.env`의 `LEAGUE_CLOCK_FIXED`·`ACTUATOR_EXPOSE` 줄을 지우고 다시 배포.
- **이력서·포트폴리오**: P1 사례(현상 → 분석 → 해결 → 재측정)를 수치와 함께 넣는다.
- **직접 할 일(이어서).** Swagger 확인, 1·2주차 "왜" 노트, docs/01 6절 결정 확인, 프로젝트가 끝나면 저장소 공개.

---

# 부록 A. 자주 쓰는 명령

```bash
docker compose up -d                       # 로컬 DB(5433)·Redis(6380) 켜기
docker compose stop                        # 끄기(데이터는 남는다)
./gradlew bootJar && docker build -t mock-invest-league-api .   # 앱 이미지
docker compose --profile app up -d         # 앱 컨테이너까지 띄우기 → http://localhost:8080
./gradlew bootRun                          # 앱 실행 → http://localhost:8080/swagger-ui.html
./gradlew bootRun --args='--league.clock.fixed=2026-12-01T10:00:00'  # 시계 고정(밤에도 주문 가능)
TEST_TIMEOUT=1800 ./run_tests.sh           # 전체 테스트(워치독 30분. 기본 600초로는 모자란다)
./run_tests.sh --tests '*Company*'         # 일부 테스트만
./run_tests.sh --tests '*ConcurrencyTest*' # 동시성 비교(방식 4개 × 시나리오 4개). 결과 줄은 [동시성]
grep -h '\[동시성\]' build/test-results/test/*Concurrency*.xml  # 비교 수치만 보기
LEAGUE_EXPERIMENT=true TEST_TIMEOUT=900 ./run_tests.sh --tests '*QueryPerformanceExperiment'  # 조회 성능 실험(약 6분)
./scripts/load-test.sh 1 2m                 # k6 부하 테스트(가정의 1배, 2분). 결과 build/k6/x1-cpu2-pool10/
CPUS=4 MEM=1400m ./scripts/load-test.sh 1 2m   # 앱 CPU·메모리 바꿔서
JFR=1 ./scripts/load-test.sh 1 2m           # JFR 기록까지(app.jfr → jfr print --events jdk.ExecutionSample)
deploy/upload.sh <EC2 IP>                   # 서버에 compose·deploy.sh 올리기(.env 는 없을 때만 틀)
read -s ADMIN_TOKEN; export ADMIN_TOKEN; scripts/load-test-remote.sh http://<EC2 IP> 1 2m laptop  # 배포한 서버에 k6(평일 장 시간)
monitoring/up.sh local                       # 그래프(Prometheus + Grafana) → http://localhost:3000. EC2 를 볼 땐 local 대신 IP
SCENARIO=hot scripts/load-test.sh 1 2m      # 시나리오 하나(steady|journey|hot|rush|tick). 결과 폴더에 report.html
curl -H "X-Admin-Token: $ADMIN_TOKEN" <서버>/api/admin/contests/<id>/invariants   # 불변식 검사
monitoring/down.sh                          # 그래프 끄기(데이터는 30일 남음)
node monitoring/capture.mjs <이름> <시작초> <끝초> [패널]   # Grafana 패널 → docs/img/*.png
./psql.sh -f /sql/autovacuum.sql            # (서버 /opt/league) RDS autovacuum 기록
./psql.sh -tA -F ' ' -f /sql/activity.sql   # (서버) 1초마다 DB 세션 대기 종류, 150초
vmstat -t 1 100                             # (서버) 1초마다 CPU·스왑·steal(st)
./gradlew bootJar && scripts/cache-bench.sh 10000 20   # 캐시 비교(없음·서버 메모리·Redis). 결과 build/bench/summary.txt
CACHE_TYPE=redis REDIS_HEALTH=true ./gradlew bootRun  # Redis 캐시로 띄우기
grep -h '\[성능\]' build/test-results/test/*QueryPerformance*.xml  # 성능 수치만 보기
./gradlew bootRun --args='--league.order.lock-strategy=CONDITIONAL'  # 락 방식 바꿔 띄우기
open build/reports/tests/test/index.html   # 테스트 결과
open build/reports/jacoco/test/html/index.html  # 커버리지
```

# 부록 B. 용어

| 용어 | 뜻 |
|---|---|
| 엔티티(Entity) | 테이블 한 줄에 대응하는 Java 객체 |
| 리포지토리(Repository) | 엔티티를 저장·조회하는 인터페이스 |
| 트랜잭션 | 여러 DB 작업을 "전부 성공 아니면 전부 취소"로 묶는 단위 |
| 불변식(Invariant) | 시스템이 어떤 순간에도 지켜야 하는 규칙 |
| 멱등성(Idempotency) | 같은 요청을 여러 번 보내도 결과가 한 번 보낸 것과 같은 성질 |
| 갱신 손실(Lost update) | 동시에 읽고 덮어써서 한쪽 변경이 사라지는 현상 |
| 비관적 락 | 읽을 때부터 잠가서 다른 트랜잭션이 기다리게 함(`SELECT ... FOR UPDATE`) |
| 낙관적 락 | 잠그지 않고, 저장할 때 `version`이 바뀌었으면 실패시킴(`@Version`) |
| 조건부 UPDATE | `set cash = cash - ? where cash >= ?`처럼 DB가 한 문장으로 계산·검사하는 방식 |
| 재시도 지터 | 실패한 요청들이 같은 순간에 다시 부딪히지 않게 대기 시간을 무작위로 흩는 것 |
| `CountDownLatch` | 숫자가 0이 될 때까지 스레드를 기다리게 하는 도구. 동시 출발선으로 쓴다 |
| 마이그레이션 | 스키마 변경을 버전 순서대로 적용하는 것(Flyway) |
| bp(베이시스 포인트) | 0.01%. 50bp = 0.5% |
| 틱(Tick) | 가격이 바뀌는 단위 시간 |
| 기하 브라운 운동(GBM) | 가격이 "직전 가격 × e^(무작위 수익률)"로 움직이는 모델. 가격이 0 아래로 가지 않는다 |
| 시드(Seed) | 난수열의 출발값. 같은 시드면 같은 난수열이 나온다 |
| 몬테카를로 | 무작위 시뮬레이션을 많이 돌려 통계로 성질을 확인하는 방법 |
| 원장(Ledger) | 돈의 이동을 추가만 하는 기록 |
| 지정가 주문 | "이 가격 이하면 사겠다/이상이면 팔겠다"는 주문. 조건이 맞을 때까지 대기한다 |
| 묶기(Reserve) | 대기 주문이 쓸 돈·주식을 따로 떼어 두는 것. 다른 주문에 쓸 수 없다 |
| 셀프 호출 문제 | 같은 클래스 안에서 부른 `@Transactional` 메서드는 프록시를 거치지 않아 트랜잭션이 안 열리는 것 |
| OSIV | Open Session In View. 요청 끝까지 DB 연결을 유지하는 설정 |
| N+1 | 목록 1번 조회 후, 항목마다 추가 쿼리가 N번 나가는 문제 |
| 영속성 컨텍스트 | JPA가 엔티티를 관리하는 공간. 트랜잭션 동안 엔티티를 기억하고 변경을 추적한다 |
| 1차 캐시 | 영속성 컨텍스트 안의 엔티티 저장소. 같은 id를 다시 찾으면 DB에 가지 않고 여기서 돌려준다 |
| 쓰기 지연 | 변경 SQL을 모았다가 flush 때 한 번에 보내는 것 |
| 더티 체킹 | 트랜잭션이 끝날 때 바뀐 필드를 찾아 UPDATE를 자동으로 만드는 것. 그래서 `save()`를 다시 부르지 않아도 된다 |
| 복합키 | 컬럼 두 개 이상으로 이루어진 기본키 |
| mock(가짜 객체) | 테스트에서 진짜 객체 대신 쓰는, 동작을 흉내 내는 객체(Mockito) |
| 공시(Disclosure) | 회사·섹터·시장에 대한 소식. 여기서는 그 틱에 가격을 옮기는 가상 이벤트 |
| 스냅샷 | 어느 순간의 상태를 계산해 저장해 둔 것. 조회는 다시 계산하지 않고 이것만 읽는다 |
| `rank()` | 정렬 순서대로 순위를 매기는 SQL 창 함수. 동점이면 같은 순위(1, 2, 2, 4) |
| `INSERT ... SELECT` | 조회 결과를 그대로 다른 테이블에 넣는 SQL. 앱으로 가져오지 않고 DB 안에서 끝난다 |
| 교착(Deadlock) | 두 트랜잭션이 서로 상대가 잡은 락을 기다려 둘 다 멈추는 것. 잠그는 순서를 같게 하면 막을 수 있다 |
| `EXPLAIN (ANALYZE, BUFFERS)` | 쿼리를 실제로 실행하고, 고른 계획·읽은 행·건드린 블록을 보여 주는 PostgreSQL 명령 |
| 플래너(Planner) | 통계를 보고 쿼리를 어떤 길(인덱스, 정렬, 조인 방식)로 실행할지 고르는 DB 부품 |
| 버퍼·블록 | DB가 데이터를 읽는 단위(8KB). 읽은 블록 수는 시간보다 덜 흔들리는 비용 지표다 |
| `LATERAL` | 바깥 행마다 안쪽 서브쿼리를 한 번씩 실행하게 하는 SQL. 안쪽에서 바깥 열을 쓸 수 있다 |
| CTE | `with 이름 as (...)`로 쿼리 앞에 이름 붙인 중간 결과 |
| 중앙값 | 값을 줄 세웠을 때 가운데 값. 가끔 튀는 값에 덜 끌려간다 |
| 캐시 무효화 | 원본이 바뀌었을 때 캐시의 예전 값을 지우는 일. 키에 버전을 넣으면 필요 없어진다 |
| TTL | Time To Live. 캐시에 넣은 값이 저절로 사라지기까지의 시간 |
| Caffeine | 자바 서버 메모리 캐시 라이브러리. 크기·시간 제한으로 오래된 값을 내보낸다 |
| 직렬화 | 객체를 바이트로 바꾸는 것. Redis처럼 프로세스 밖에 저장하려면 필요하다 |
| ApacheBench(`ab`) | 같은 요청을 정해진 수만큼 동시에 보내 처리량·응답 시간을 재는 도구 |
| 이미지 계층(layer) | Docker 이미지를 이루는 층. 내용이 같은 층은 다시 만들지도 올리지도 않는다 |
| 헬스체크 | 컨테이너가 떠 있을 뿐 아니라 제대로 응답하는지 주기적으로 확인하는 것 |
| compose 프로필 | `--profile`을 줄 때만 뜨는 서비스 묶음 |
| CI | Continuous Integration. 코드를 올릴 때마다 자동으로 빌드·테스트하는 것 |
| GitHub Actions | GitHub가 제공하는 CI. `.github/workflows/*.yml`에 단계를 적는다 |
| 프로필(Spring) | `application-prod.yml`처럼 환경별 설정 묶음. `SPRING_PROFILES_ACTIVE`로 고른다 |
| 컨테이너 레지스트리 | 이미지를 올려 두고 서버가 받아 가는 저장소(ghcr.io, ECR, Docker Hub) |
| RDS | AWS가 운영해 주는 관계형 DB. 백업·패치·장애 복구를 맡는다 |
| 보안 그룹 | AWS의 가상 방화벽. 어떤 포트를 어디서 들어오게 할지 정한다 |
| graceful shutdown | 내려갈 때 새 요청은 막고 처리 중인 요청은 끝낸 뒤 종료하는 것 |
| 롤백(되돌리기) | 새 버전이 문제일 때 직전 버전으로 돌아가는 것 |
| 타이밍 공격 | 비교에 걸리는 시간 차이로 비밀값을 한 글자씩 알아내는 공격 |
| k6 | 자바스크립트로 시나리오를 쓰는 부하 테스트 도구 |
| p95 | 응답 시간을 줄 세웠을 때 95% 지점. "100명 중 95명은 이 시간 안에 받았다" |
| `constant-arrival-rate` | 서버 응답과 상관없이 초당 정해진 수의 요청을 보내는 k6 방식 |
| coordinated omission | 서버가 느려질 때 부하 발생기도 덩달아 덜 보내 결과가 좋게 나오는 측정 오류 |
| JFR | JDK Flight Recorder. JVM에 들어 있는 프로파일러. CPU 표본, 잠금 대기, GC를 기록한다 |
| 503 / `Retry-After` | "지금은 처리할 수 없으니 잠시 뒤 다시". `Retry-After`는 몇 초 뒤에 오라는 헤더 |
| SerialGC / G1 | JVM의 GC 방식. 작은 컨테이너에서는 JVM이 단순한 SerialGC를 고르고, 보통은 G1을 쓴다 |
| load average | 실행 중이거나 CPU를 기다리는 작업 수의 평균. 코어 수보다 크면 CPU가 붐빈다 |
| 보안 그룹 | AWS 리소스에 붙는 방화벽. 들어오는 규칙의 소스로 IP 대역이나 다른 보안 그룹을 지정한다 |
| VPC | AWS 안의 내 사설 네트워크. 같은 VPC 안의 EC2와 RDS는 내부 주소(172.31.x.x)로 통신한다 |
| 엔드포인트(RDS) | DB에 붙을 때 쓰는 호스트 이름. VPC 안에서는 내부 IP로 풀린다 |
| 사용자 데이터 / cloud-init | EC2가 처음 켜질 때 한 번 실행하는 스크립트. `cloud-init status`로 끝났는지 본다 |
| PAT(classic) | GitHub 개인 액세스 토큰. 권한 범위(scope)를 골라 만든다. ghcr.io 이미지 받기에 `read:packages` |
| CloudShell | AWS 콘솔 안에서 여는 리눅스 셸. 내 계정의 리전 안에서 돈다 |
| `read -s` | 입력을 화면에 보이지 않게 셸 변수로 받는 명령. 비밀값을 명령줄·기록에 남기지 않는다 |
| Prometheus | 서버의 지표를 주기적으로 가져와(scrape) 시간 순으로 저장하는 도구 |
| Grafana | Prometheus 같은 저장소의 지표를 그래프 대시보드로 보여주는 도구 |
| 히스토그램(지표) | 값을 구간별 개수로 센 것. 이게 있어야 "특정 시간 구간의 p95"를 나중에 계산할 수 있다 |
| 스냅샷 격리 | 트랜잭션 안의 모든 쿼리가 시작 시점의 같은 데이터를 보는 것(PostgreSQL REPEATABLE READ) |
| 캐시 스탬피드 | 캐시가 한꺼번에 비면서 그 순간 모든 요청이 DB 로 몰리는 현상 |
| 장애 주입 | 일부러 DB 재부팅 같은 장애를 일으켜 복구 동작을 확인하는 시험 |
| SIGHUP | 프로세스에 "설정을 다시 읽어라"로 흔히 쓰는 신호. 처리기가 없으면 기본 동작은 종료다 |
| steal(`st`) | 가상 서버가 CPU 를 쓰려 했는데 하이퍼바이저가 그 시간을 다른 데 준 비율 |
| 버스트형 인스턴스 | t3 처럼 평소 기본 성능만 보장하고 CPU 를 다른 서버와 나눠 쓰는 저가 인스턴스 |
| 토큰 버킷 | 통에 토큰이 일정 속도로 차고, 요청 하나가 토큰 하나를 쓰는 속도 제한 방식 |
| 429 Too Many Requests | 요청이 너무 잦다는 HTTP 상태. `Retry-After` 로 언제 다시 보낼지 알린다 |
| 인터셉터 | Spring MVC 에서 컨트롤러 앞뒤에 끼어드는 코드. 트랜잭션보다 먼저 돈다 |
| `pg_stat_activity` | PostgreSQL 의 현재 세션 목록. 무엇을 기다리는지(wait_event)가 보인다 |
| autovacuum | PostgreSQL 이 바뀌거나 지운 행의 흔적을 주기적으로 치우는 작업 |
| `vmstat` | 1초 단위로 CPU·메모리·스왑·디스크 상태를 찍는 리눅스 명령 |
