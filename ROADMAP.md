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
| 15 | 다음 할 일 | 다음 |

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

# 15단계. 다음 할 일

- **1주차에서 남은 것(직접 할 일).** Swagger로 사용자 생성 → 대회 생성·시작 → 참가 → 매수·매도 → 내 계좌를 눌러 본다(`league.clock.fixed`). `docs/notes/week1-왜.md` 1쪽을 쓴다.
- **2주차 "왜" 노트.** 14-6 비교표와 14-7 선택 이유를 내 말로 1쪽에 줄인다. 2주차를 끝내는 기준이다.
- **3주차.** 지정가 주문(묶인 현금·수량), 틱 가격 엔진(시드 고정), 장 마감 배치(재실행 안전, I10).

---

# 부록 A. 자주 쓰는 명령

```bash
docker compose up -d                       # 로컬 DB 켜기
./gradlew bootRun                          # 앱 실행 → http://localhost:8080/swagger-ui.html
./gradlew bootRun --args='--league.clock.fixed=2026-12-01T10:00:00'  # 시계 고정(밤에도 주문 가능)
./run_tests.sh                             # 전체 테스트
./run_tests.sh --tests '*Company*'         # 일부 테스트만
./run_tests.sh --tests '*ConcurrencyTest*' # 동시성 비교(방식 4개 × 시나리오 4개). 결과 줄은 [동시성]
grep -h '\[동시성\]' build/test-results/test/*Concurrency*.xml  # 비교 수치만 보기
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
| 원장(Ledger) | 돈의 이동을 추가만 하는 기록 |
| OSIV | Open Session In View. 요청 끝까지 DB 연결을 유지하는 설정 |
| N+1 | 목록 1번 조회 후, 항목마다 추가 쿼리가 N번 나가는 문제 |
| 영속성 컨텍스트 | JPA가 엔티티를 관리하는 공간. 트랜잭션 동안 엔티티를 기억하고 변경을 추적한다 |
| 1차 캐시 | 영속성 컨텍스트 안의 엔티티 저장소. 같은 id를 다시 찾으면 DB에 가지 않고 여기서 돌려준다 |
| 쓰기 지연 | 변경 SQL을 모았다가 flush 때 한 번에 보내는 것 |
| 더티 체킹 | 트랜잭션이 끝날 때 바뀐 필드를 찾아 UPDATE를 자동으로 만드는 것. 그래서 `save()`를 다시 부르지 않아도 된다 |
| 복합키 | 컬럼 두 개 이상으로 이루어진 기본키 |
| mock(가짜 객체) | 테스트에서 진짜 객체 대신 쓰는, 동작을 흉내 내는 객체(Mockito) |
