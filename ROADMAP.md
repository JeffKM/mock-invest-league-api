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
| 12 | 시장가 주문 서비스(직접 짜기) | 다음 |

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

# 12단계. 다음 할 일: 시장가 주문(직접 짜기)

- 1주차는 가격 엔진이 없으므로 임시 현재가 = `ContestListing.getBasePrice()`.
- 이제 엔티티가 규칙을 갖고 있으므로, 서비스는 **순서와 트랜잭션 경계**만 책임진다.
- `OrderService.placeMarketOrder` 하나의 `@Transactional` 안에서:
  1. 같은 키의 주문이 이미 있으면 그 결과를 돌려준다(`findByParticipantIdAndIdempotencyKey`)
  2. 대회 진행 중·장 시간인지 확인(`Contest.isRunning()`, 장 시간 판단은 `Clock`으로 직접 작성)
  3. 현재가로 금액·수수료 계산(수수료 함수는 직접 작성: 원 미만 버림)
  4. 매수: `participant.withdraw(금액 + 수수료)`, `holding.increase(...)` / 매도: `holding.decrease(...)`, `participant.deposit(금액 − 수수료)`
  5. `Order.market(...)` 저장 → `order.fill(...)`
  6. 원장 `BUY`/`SELL`, `FEE` 기록
- 일부러 아직 락을 걸지 않는다. 2주차에 동시 요청 100개로 갱신 손실을 재현한 뒤 세 가지 방법(비관적 락, 낙관적 락, 조건부 UPDATE)을 비교한다.
- 통합 테스트:
  - 매수 후 현금 = 초기 자금 − 금액 − 수수료
  - 잔고 초과 매수 → 409, 잔고 그대로(트랜잭션 롤백 확인)
  - 보유 초과 매도 → 409, 보유 그대로
  - I5: 여러 번 사고판 뒤 `cash + reserved_cash = Σ 원장`(INITIAL 포함)
  - 같은 `Idempotency-Key` 순차 두 번 → 주문 1건

---

# 부록 A. 자주 쓰는 명령

```bash
docker compose up -d                       # 로컬 DB 켜기
./gradlew bootRun                          # 앱 실행 → http://localhost:8080/swagger-ui.html
./run_tests.sh                             # 전체 테스트
./run_tests.sh --tests '*Company*'         # 일부 테스트만
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
| 마이그레이션 | 스키마 변경을 버전 순서대로 적용하는 것(Flyway) |
| bp(베이시스 포인트) | 0.01%. 50bp = 0.5% |
| 틱(Tick) | 가격이 바뀌는 단위 시간 |
| 원장(Ledger) | 돈의 이동을 추가만 하는 기록 |
| OSIV | Open Session In View. 요청 끝까지 DB 연결을 유지하는 설정 |
| N+1 | 목록 1번 조회 후, 항목마다 추가 쿼리가 N번 나가는 문제 |
| 영속성 컨텍스트 | JPA가 엔티티를 관리하는 공간. 트랜잭션 동안 엔티티를 기억하고 변경을 추적한다 |
| 1차 캐시 | 영속성 컨텍스트 안의 엔티티 저장소. 같은 id를 다시 찾으면 DB에 가지 않고 여기서 돌려준다 |
| 쓰기 지연 | 변경 SQL을 모았다가 flush 때 한 번에 보내는 것 |
| 더티 체킹 | 트랜잭션이 끝날 때 바뀐 필드를 찾아 UPDATE를 자동으로 만드는 것. 그래서 `save()`를 다시 부르지 않아도 된다 |
| 복합키 | 컬럼 두 개 이상으로 이루어진 기본키 |
| mock(가짜 객체) | 테스트에서 진짜 객체 대신 쓰는, 동작을 흉내 내는 객체(Mockito) |
