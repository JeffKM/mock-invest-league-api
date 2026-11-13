# mock-invest-league-api

여러 모의투자 대회를 동시에 운영하는 Spring Boot 서버. 참가자는 대회마다 같은 가상 자금으로 가상 상장사 주식을 사고팔아 총자산 순위를 겨룹니다.

> 진행 중(5주차: 배포 준비·부하 테스트). 가상 상장사·공시는 모두 지어낸 것이며 실존 기업과 관계없습니다.

## 이 프로젝트가 풀려는 문제

1. **동시 주문과 잔고 정합성:** 같은 계좌에 주문이 동시에 몰려도 돈이 생기거나 사라지지 않는다(비관적 락·낙관적 락·조건부 UPDATE 비교)
2. **멱등성:** 같은 주문 요청이 재전송돼도 한 번만 체결
3. **조회 성능:** 시세판·순위 쿼리 EXPLAIN 개선, 틱 경계에 맞춘 캐시 만료
4. **폐장 배치와 지정가 소급 정산:** 재실행해도 결과가 같은 배치
5. **부하 테스트와 운영:** k6, 모니터링, 배포

## 실행

```bash
docker compose up -d          # 로컬 PostgreSQL(5433)
./gradlew bootRun             # http://localhost:8080/swagger-ui.html
./run_tests.sh                # 테스트(도커 필요, 워치독 포함)
./scripts/load-test.sh 1 2m   # k6 부하 테스트(앱 이미지·k6 필요)
```

배포(AWS EC2 + RDS)는 [docs/05](docs/05-배포-AWS.md)의 순서를 따른다. 운영은 `SPRING_PROFILES_ACTIVE=prod`이고, DB 주소·비밀번호·관리자 토큰을 환경 변수로 받는다.

## 문서

- [요구사항·불변식](docs/01-요구사항-불변식.md)
- [가상 상장사](docs/02-가상-상장사.md)
- [ERD·모듈 경계](docs/03-ERD.md)
- [1주차 할 일](docs/04-1주차-할일.md)
- [배포: AWS EC2 + RDS](docs/05-배포-AWS.md)
- [로드맵·복습 노트](ROADMAP.md)

## 기술

Java 17 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL 16 · Flyway · Testcontainers · JaCoCo · springdoc-openapi
