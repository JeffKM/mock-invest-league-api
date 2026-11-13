# 배포: AWS EC2 + RDS

> 5주차②. 코드와 스크립트는 준비돼 있다. 계정·결제·서버 만들기는 직접 한다.
> 금액은 서울 리전 기준 대략값이다. 만들기 전에 [AWS 요금 계산기](https://calculator.aws/)로 다시 확인한다.

## 1. 구성

```
k6(노트북) ──HTTP:80──▶ EC2 t3.micro (Docker: league-app)
                          │  이미지: ghcr.io/jeffkm/mock-invest-league-api:<커밋 sha>
                          └──5432(같은 VPC 안)──▶ RDS PostgreSQL 16 (db.t4g.micro, 인터넷에서 접근 불가)
```

- **앱 서버 한 대.** 캐시는 서버 메모리(Caffeine)라 Redis가 필요 없다(19단계).
- **DB는 RDS.** 백업·패치·장애 복구를 AWS가 맡는다. EC2에 Postgres를 같이 띄우면 싸지만, 서버가 죽으면 데이터도 같이 위험하다.
- **이미지는 GitHub Container Registry(ghcr.io).** CI가 main에 올라온 커밋마다 이미지를 커밋 sha 태그로 올린다. AWS 쪽에 레지스트리(ECR)를 따로 두지 않아도 되고, CI에 AWS 키를 넣지 않아도 된다.

## 2. 비용: 무료 플랜 크레딧으로 낸다

**결론: 무료 플랜으로 가입하면 실제로 내는 돈은 0원이다.** 아래 표는 정가이고, 이 금액이 내 카드가 아니라 **크레딧에서 빠진다.**

- 2025년 7월 15일 이후 가입한 계정의 프리 티어는 "12개월 무료"가 아니라 **크레딧 방식**이다. 가입 때 $100, 주요 서비스를 써 보면 최대 $100를 더 받아 **최대 $200**.
- **무료 플랜(Free plan)**은 가입 후 **6개월** 또는 **크레딧을 다 쓸 때**까지다. 유료 플랜으로 바꾸지 않으면 **요금이 청구되지 않고**, 기간이 끝나거나 크레딧이 떨어지면 계정이 닫힌다(그 전에 메일로 알려 준다).
- RDS는 무료 플랜에서 PostgreSQL db.t4g.micro를 쓸 수 있다. EC2 t3.micro도 크레딧으로 낼 수 있다.
- 24시간 켜 두면 한 달 약 $30~37이 크레딧에서 빠진다. 크레딧 $200이면 약 5~6개월, $100만 받았으면 약 3개월이다. **데모할 때만 켜면** 훨씬 오래 간다.
- **주의:** 유료 플랜으로 바꾸면 크레딧을 넘는 사용량은 그때부터 청구된다. 2025년 7월 15일 이전에 만든 계정은 예전 조건(12개월 동안 t2/t3.micro·RDS micro 750시간 무료)을 따른다. 내 계정의 크레딧 잔액과 남은 기간은 콘솔 **Billing → Free Tier / Credits**에서 확인한다.

정가(서울 리전, 대략):

| 항목 | 사양 | 한 달(24시간 켜 둘 때) |
|---|---|---|
| EC2 | t3.micro(2 vCPU, 1GB) | 약 $10 |
| RDS | db.t4g.micro, 단일 AZ | 약 $15~20 |
| RDS 저장 공간 | gp3 20GB | 약 $3 |
| 공인 IPv4 | EC2에 붙는 주소 1개($0.005/시간) | 약 $4 |
| 합계 | | **약 $30~37** |

- **t3.micro의 CPU 크레딧.** t3는 평소 CPU의 10%만 보장하고, 남는 시간에 크레딧을 모아 두었다가 바쁠 때 쓴다. 기본 설정(unlimited)은 크레딧이 떨어져도 계속 빠르게 돌고 **초과분을 따로 청구**한다. 만들 때 **크레딧 사양을 standard**로 바꾸면 추가 요금 대신 느려진다.
- 데모할 때만 켜 둔다. RDS는 "일시 중지"가 최대 7일이라(그 뒤 자동으로 다시 켜진다), 오래 안 쓸 거면 스냅샷을 남기고 삭제한다.

## 3. 처음 한 번: 계정

1. AWS 가입(직접). 플랜을 고르는 화면에서 **무료 플랜(Free plan)**을 고른다(2절). 루트 계정에 **MFA**를 켠다.
2. **예산 알림부터 만든다.** Billing → Budgets → 월 $10(또는 원하는 금액), 80%·100%에서 메일. 설정 실수로 과금되는 것을 가장 먼저 막는다.
3. 리전을 **서울(ap-northeast-2)**로 고정한다.

## 4. 보안 그룹 두 개

| 이름 | 들어오는 규칙 | 이유 |
|---|---|---|
| `league-app` | HTTP 80: 어디서나(0.0.0.0/0) | API 공개 |
| | SSH 22: **내 IP만** | 서버 접속. 전체에 열면 하루 만에 무작위 대입 공격이 들어온다 |
| `league-db` | PostgreSQL 5432: **소스 = `league-app` 보안 그룹** | 앱 서버만 DB에 붙는다. IP가 아니라 그룹으로 지정해서 EC2 주소가 바뀌어도 그대로다 |

## 5. RDS 만들기

- 엔진: PostgreSQL 16. 템플릿: 무료 플랜이 보이면 그것, 아니면 "개발/테스트"
- 인스턴스: db.t4g.micro, 단일 AZ
- 저장 공간: gp3 20GB, **자동 확장 끄기**
- 마스터 사용자 `league`, 비밀번호는 직접 정해서 비밀번호 관리자에 저장한다
- 연결: **퍼블릭 액세스 "아니요"**, 보안 그룹 `league-db`
- 추가 구성: **초기 데이터베이스 이름 `league`**(비우면 DB가 안 만들어진다), 자동 백업 7일
- 만든 뒤 "엔드포인트"를 적어 둔다(예: `league.xxxx.ap-northeast-2.rds.amazonaws.com`)

PostgreSQL 15부터 RDS는 기본으로 SSL 연결만 받는다. 그래서 `DB_URL`에 `?sslmode=require`를 붙인다. 테이블은 앱이 처음 뜰 때 Flyway가 만든다.

## 6. EC2 만들기

- AMI: Amazon Linux 2023(x86_64). CI가 만드는 이미지가 x86용이라 ARM(t4g)이 아니라 **t3.micro**로 한다
- 키 페어: 새로 만들고 `.pem`을 안전한 곳에 둔다
- 보안 그룹: `league-app`
- 고급 세부 정보:
  - **크레딧 사양: standard**(2절)
  - **사용자 데이터:** `deploy/ec2-setup.sh` 내용을 붙여 넣는다. Docker와 compose를 설치하고, 스왑 1GB를 만들고, `/opt/league`를 만든다
- (선택) 탄력적 IP를 붙이면 서버를 껐다 켜도 주소가 바뀌지 않는다

## 7. 서버에 파일 올리기

```bash
EC2=ec2-user@<EC2 공인 IP>
scp -i league.pem deploy/docker-compose.yml deploy/deploy.sh $EC2:/opt/league/
scp -i league.pem deploy/.env.example $EC2:/opt/league/.env
ssh -i league.pem $EC2
# 서버에서
cd /opt/league && chmod 600 .env && vi .env      # DB_URL·DB_PASSWORD·ADMIN_TOKEN 채우기
openssl rand -hex 32                               # ADMIN_TOKEN 으로 쓸 값
```

**이미지 받기 권한.** 저장소가 비공개라 ghcr.io 이미지도 비공개다. GitHub → Settings → Developer settings → Personal access tokens(classic)에서 **`read:packages` 권한만** 가진 토큰을 만들고, 서버에서 한 번 로그인한다.

```bash
docker login ghcr.io -u jeffkm     # 비밀번호 자리에 토큰
```

비공개 패키지는 GitHub 요금제의 Packages 저장 용량·전송량 한도에 들어간다. 오래된 이미지 버전은 저장소 → Packages에서 지운다.

## 8. 배포와 되돌리기

1. 코드를 main에 올린다. CI가 통과하면 **Actions 실행 요약**에 `올린 이미지: ghcr.io/jeffkm/mock-invest-league-api:<sha>`가 나온다.
2. 서버에서:

```bash
cd /opt/league
./deploy.sh ghcr.io/jeffkm/mock-invest-league-api:<sha>
```

`deploy.sh`가 하는 일:

1. 이미지를 먼저 받는다. 받기에 실패하면 지금 도는 앱은 건드리지 않는다.
2. 새 이미지로 컨테이너를 바꾼다.
3. 헬스 체크가 healthy가 될 때까지 최대 180초 기다린다.
4. 성공하면 그 이미지를 `.deployed-image`에 적는다(다음 배포가 실패했을 때 되돌아갈 곳).
5. 실패하면 로그 50줄을 보여 주고 **직전 이미지로 되돌린다.** 처음 배포라 되돌릴 곳이 없으면 앱을 멈춘다(죽었다 살아나기를 반복하지 않게).

확인:

```bash
curl http://<EC2 IP>/actuator/health                     # {"status":"UP"}
curl -X POST http://<EC2 IP>/api/admin/contests \
  -H "X-Admin-Token: <ADMIN_TOKEN>" -H 'Content-Type: application/json' \
  -d '{"name":"12월 리그","startsOn":"2026-12-01","endsOn":"2026-12-31"}'
```

Swagger(`/swagger-ui.html`)도 열려 있다. 관리자 API는 `X-Admin-Token` 헤더가 없으면 401이다.

**알아 둘 것:** 서버가 한 대라 배포하는 동안(기존 컨테이너를 내리고 새것이 뜨기까지 약 1분) 요청을 받지 못한다. 무중단으로 바꾸려면 서버 두 대와 로드 밸런서(ALB)가 필요하다. 지금 규모에는 과하다고 보고 미뤘다.

## 9. 배포한 서버에 k6

로컬 측정(ROADMAP 21단계)은 앱·DB·k6가 한 노트북을 나눠 썼다. 배포하면 처음으로 **부하 발생기(노트북)와 앱(EC2)이 다른 기계**가 된다.

```bash
# 서버 .env 에 잠깐 ACTUATOR_EXPOSE=health,info,metrics 를 넣고 ./deploy.sh 로 다시 띄운다(끝나면 되돌린다)
k6 run -e BASE_URL=http://<EC2 IP> -e ADMIN_TOKEN=<토큰> -e MULT=1 -e DURATION=2m \
  -e STARTS_ON=<오늘> -e ENDS_ON=<한 달 뒤> scripts/k6/league.js
```

- 운영 서버는 시계를 고정하지 않으므로 **장 시간(평일 09:00~15:30)에** 돌려야 주문이 받아진다.
- 대회와 사용자 1,000명이 운영 DB에 남는다. 측정용이면 끝나고 RDS를 스냅샷 없이 지우거나, 측정 전에 스냅샷을 떠 둔다.
- 노트북과 서울 리전 사이 네트워크 왕복 시간이 응답 시간에 더해진다. `curl -w '%{time_connect}'`로 먼저 재 둔다.
- t3.micro는 크레딧이 떨어지면 느려진다(2절). 짧게 돌리고, CloudWatch의 `CPUCreditBalance`를 같이 본다.

## 10. 끄기(비용 멈추기)

1. EC2: 종료(terminate). 탄력적 IP를 붙였다면 **해제(release)**까지 해야 요금이 멈춘다
2. RDS: 삭제. 데이터가 필요하면 최종 스냅샷을 남긴다(스냅샷도 저장 요금이 있다)
3. 보안 그룹·키 페어는 요금이 없다
4. 다음 달 청구서에서 0이 되었는지 확인한다

## 11. 아직 하지 않은 것

| 할 일 | 필요한 것 | 미룬 이유 |
|---|---|---|
| HTTPS | 도메인 + 인증서(Caddy 같은 리버스 프록시, 또는 ALB + ACM) | 도메인 구매·DNS가 필요하다. 데모용 API라 우선 HTTP |
| 무중단 배포 | 서버 2대 + ALB | 비용이 두 배 이상. 지금 규모에 과하다 |
| 자동 배포(CD) | Actions가 서버에 접속할 권한(SSM 또는 SSH 키) | 서버에 들어가는 키를 CI에 두는 위험을 먼저 따져 본다 |
| 로그·지표 수집 | CloudWatch Agent 또는 Prometheus·Grafana | 우선 `docker logs`와 `/actuator/health`로 충분 |
| 스케줄러 중복 실행 방지 | ShedLock 같은 분산 락 | 서버가 한 대라 아직 필요 없다. 두 대가 되면 배치가 두 번 돈다(스냅샷·가격 생성은 유니크 키로 막혀 있지만 정산 배치는 확인 필요) |
