# 배포: AWS EC2 + RDS

> 5주차②에 준비하고, 6주차에 실제로 올렸다(ROADMAP 23단계). 계정·비밀값·서버 만들기는 직접 한다.
> **리전은 시드니(ap-southeast-2)다.** 무료 플랜 계정은 리전이 하나로 고정되고, 이 계정은 시드니였다(3절). 금액은 대략값이다. [AWS 요금 계산기](https://calculator.aws/)로 다시 확인한다.

## 1. 구성

```
k6(노트북, 한국) ──HTTP:80──▶ EC2 t3.micro (시드니, Docker: league-app)
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

정가(대략. 시드니는 서울과 비슷하거나 조금 비싸다):

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
3. **리전 확인.** 무료 플랜 계정은 "프로젝트"에 리전이 하나 정해져 있고, 다른 리전에 들어가면 "리전 사용 불가"가 뜬다(유료 플랜으로 올려야 풀린다). 이 계정은 **시드니(ap-southeast-2)**였다. 새 콘솔에는 리전 메뉴가 없어서, 주소창의 `region=`으로 지금 리전을 확인한다.
   - 한국에서 시드니까지 왕복 약 0.13~0.3초가 응답 시간에 더해진다. 데모에는 지장 없고, 측정할 때는 따로 재서 뺀다(9절).
4. 시작 화면의 "무엇을 생성하시겠습니까?"와 "AWS용 에이전트 툴킷"은 건너뛴다. 툴킷은 이 맥에 AWS 키를 두고 에이전트가 리소스를 만들게 하는 것이라, 리소스 몇 개뿐인 지금은 위험만 늘린다.

## 4. 보안 그룹 두 개

| 이름 | 들어오는 규칙 | 이유 |
|---|---|---|
| `league-app` | HTTP 80: 어디서나(0.0.0.0/0) | API 공개 |
| | SSH 22: **내 IP만** | 서버 접속. 전체에 열면 하루 만에 무작위 대입 공격이 들어온다 |
| `league-db` | PostgreSQL 5432: **소스 = `league-app` 보안 그룹** | 앱 서버만 DB에 붙는다. IP가 아니라 그룹으로 지정해서 EC2 주소가 바뀌어도 그대로다 |

## 5. RDS 만들기

- 엔진: PostgreSQL **16.x 중 가장 높은 것**(기본값 18이 아니라. 로컬·테스트가 16이다). "RDS 확장 지원"은 끈다. 템플릿: 무료 플랜
- 인스턴스: db.t4g.micro, 단일 AZ
- 저장 공간: gp3 20GB, **자동 확장 끄기**
- 마스터 사용자 `league`, 자격 증명 관리는 **자체 관리**(Secrets Manager는 따로 요금), 비밀번호는 직접 정해서 비밀번호 관리자에 저장한다
- 연결: **퍼블릭 액세스 "아니요"**, 보안 그룹은 `league-db`를 넣고 **미리 골라져 있는 `default`를 반드시 뺀다**(9-1절 ①). 향상된 모니터링은 끈다
- 추가 구성: **초기 데이터베이스 이름 `league`**(비우면 DB가 안 만들어진다), 자동 백업 7일
- 만든 뒤 "연결 및 보안"에서 **엔드포인트**와 **VPC 보안 그룹이 `league-db` 하나인지** 확인한다(예: `league.xxxx.ap-southeast-2.rds.amazonaws.com`)

PostgreSQL 15부터 RDS는 기본으로 SSL 연결만 받는다. 그래서 `DB_URL`에 `?sslmode=require`를 붙인다. 테이블은 앱이 처음 뜰 때 Flyway가 만든다.

## 6. EC2 만들기

- AMI: Amazon Linux 2023(x86_64). CI가 만드는 이미지가 x86용이라 ARM(t4g)이 아니라 **t3.micro**로 한다
- 키 페어: 새로 만들고(RSA, .pem) `mv ~/Downloads/league.pem ~/.ssh/ && chmod 400 ~/.ssh/league.pem`. 다시 받을 수 없다
- 보안 그룹: 기존 그룹 선택 → `league-app`만
- 스토리지: **20GiB gp3**(배포마다 이미지가 쌓여 기본 8GiB는 빠듯하다)
- 고급 세부 정보:
  - **크레딧 사양: standard**(2절)
  - **사용자 데이터:** `deploy/ec2-setup.sh` 내용을 붙여 넣는다. Docker와 compose를 설치하고, 스왑 1GB를 만들고, `/opt/league`를 만든다
- (선택) 탄력적 IP를 붙이면 서버를 껐다 켜도 주소가 바뀌지 않는다

## 7. 서버에 파일 올리기

노트북에서(긴 명령은 붙여 넣을 때 줄이 잘려 깨지므로 스크립트 하나로 묶었다):

```bash
deploy/upload.sh <EC2 공인 IP>     # compose·deploy.sh 는 덮어쓰고, .env 는 서버에 없을 때만 틀을 올린다
ssh -i ~/.ssh/league.pem ec2-user@<EC2 공인 IP>
```

서버에서(**비밀값은 이 단계에서만, 서버 위에서 직접 다룬다**):

```bash
cd /opt/league
sed -i 's#<RDS 엔드포인트>#league.xxxx.ap-southeast-2.rds.amazonaws.com#' .env
openssl rand -hex 32     # ADMIN_TOKEN. 비밀번호 관리자에 저장
nano .env                # DB_PASSWORD, ADMIN_TOKEN 채우기
```

**토큰이 두 개다. 헷갈리기 쉽다(9-1절 ②).**

| 토큰 | 모양 | 쓰는 곳 |
|---|---|---|
| GitHub 토큰(classic) | `ghp_` + 36자 = 40자 | 서버의 `docker login ghcr.io` 한 번 |
| 관리자 토큰 | 0-9a-f 64자 | `.env`의 `ADMIN_TOKEN`, 요청의 `X-Admin-Token` 헤더 |

**이미지 받기 권한.** 저장소가 비공개라 ghcr.io 이미지도 비공개다. GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)**에서 **`read:packages` 권한만** 가진 토큰을 만들고(만료 90일. ghcr.io는 fine-grained 토큰을 받지 않는다), 서버에서 한 번 로그인한다.

```bash
docker login ghcr.io -u JeffKM     # Password 자리에 GitHub 토큰
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
read -s TOKEN                                            # 관리자 토큰 붙여넣기(화면에 안 보인다)
curl -X POST http://<EC2 IP>/api/admin/contests \
  -H "X-Admin-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"12월 리그","startsOn":"2026-12-01","endsOn":"2026-12-31"}'
```

Swagger(`/swagger-ui.html`)도 열려 있다. 관리자 API는 `X-Admin-Token` 헤더가 없으면 401이다.

**알아 둘 것:** 서버가 한 대라 배포하는 동안(기존 컨테이너를 내리고 새것이 뜨기까지 약 1분) 요청을 받지 못한다. 무중단으로 바꾸려면 서버 두 대와 로드 밸런서(ALB)가 필요하다. 지금 규모에는 과하다고 보고 미뤘다.

## 9. 배포한 서버에 k6

로컬 측정(ROADMAP 21단계)은 앱·DB·k6가 한 노트북을 나눠 썼다. 배포하면 처음으로 **부하 발생기와 앱이 다른 기계**가 된다. 러너는 `scripts/load-test-remote.sh`다(서버는 건드리지 않는다).

**두 군데에서 잰다.** 통과 기준(주문 p95 300ms, 조회 p95 200ms)은 서버 처리 시간을 보려는 것인데, 한국 노트북에서 시드니로 보내면 왕복만 약 0.3초다.

| 어디서 | 무엇을 본다 |
|---|---|
| **AWS CloudShell**(콘솔 왼쪽 아래, 같은 시드니 리전, 무료) | 네트워크가 거의 없는 상태의 **서버 처리 능력**. 통과 기준은 이걸로 판단한다 |
| **노트북**(한국) | 한국 사용자가 실제로 겪는 응답 시간. 러너가 먼저 잰 왕복 시간을 빼고 읽는다 |

```bash
read -s ADMIN_TOKEN; export ADMIN_TOKEN       # 관리자 토큰(64자)
scripts/load-test-remote.sh http://<EC2 IP> 1 2m laptop      # 가정의 1배, 2분
scripts/load-test-remote.sh http://<EC2 IP> 2 2m laptop      # 2배
```

CloudShell 준비(한 번): 저장소가 비공개라 `git clone` 대신 파일 두 개를 올린다(CloudShell 오른쪽 위 **작업 → 파일 업로드**).

```bash
mkdir -p league/scripts/k6 && mv league.js league/scripts/k6/ && mv load-test-remote.sh league/scripts/ && chmod +x league/scripts/*.sh
curl -fsSLO https://github.com/grafana/k6/releases/download/v2.3.0/k6-v2.3.0-linux-amd64.tar.gz
tar xzf k6-v2.3.0-linux-amd64.tar.gz && mkdir -p ~/.local/bin && mv k6-v2.3.0-linux-amd64/k6 ~/.local/bin/
cd league && scripts/load-test-remote.sh http://<EC2 IP> 1 2m cloudshell
```

러너가 하는 일:

1. **한국 평일 09:05~15:00가 아니면 멈춘다.** 운영 서버는 시계를 고정하지 않아 장 밖에서는 주문이 전부 거절된다. 측정 중 장이 닫히지 않게 여유를 뒀다.
2. 오늘 시작하는 측정용 대회를 만들고 사용자 1,000명을 참가시킨다(`STARTS_ON`=오늘).
3. 왕복 시간을 5번 재서 `rtt-summary.txt`에 남긴다.
4. 서버 지표를 전후로 남긴다. 서버 `.env`에 잠깐 `ACTUATOR_EXPOSE=health,info,metrics`를 넣고 `./deploy.sh`로 다시 띄워야 보인다(끝나면 되돌린다. 지표를 인터넷에 열어 두지 않는다).
5. k6를 워치독(기본 20분) 아래에서 돌리고 `build/k6/remote-<LABEL>-<시각>/`에 결과를 둔다.

주의:

- 측정용 대회와 사용자 1,000명이 운영 DB에 남는다. 측정 전에 RDS 스냅샷을 떠 두거나, 측정용이라 보고 그대로 둔다.
- t3.micro는 CPU 크레딧이 떨어지면 느려진다(2절). 짧게 돌리고, CloudWatch의 `CPUCreditBalance`를 같이 본다. 서버를 켜 둔 시간만큼 크레딧이 쌓인다.

## 9-1. 막혔던 곳(첫 배포)

| 증상 | 원인 | 고친 것 |
|---|---|---|
| ① `deploy.sh`가 180초 뒤 실패, 로그 끝에 `SocketTimeoutException: Connect timed out` | RDS에 `default` 보안 그룹만 붙어 있었다. `default`는 같은 그룹끼리만 받으므로 `league-app`이 붙은 EC2의 연결이 버려졌다 | RDS 수정 → `league-db` 추가, `default` 빼기 → 즉시 적용. 재시작 없이 1~2분 |
| ② 관리자 API가 계속 401 | GitHub 토큰(40자)을 관리자 토큰 자리에 붙여 넣었다 | 길이(`wc -c`)로 확인. 관리자 토큰은 64자 |
| ③ `scp` 명령이 깨지고, 뒷줄의 `deploy/deploy.sh …`가 **노트북에서** 실행됨 | 긴 한 줄 명령을 붙여 넣을 때 줄바꿈이 끼었다 | `deploy/upload.sh`로 묶었다. 노트북의 `deploy.sh`는 이미지 받기에서 먼저 실패해 아무것도 바꾸지 않았다 |

읽는 법: **`Connect timed out`은 네트워크(보안 그룹)**, `UnknownHostException`은 주소, `password authentication failed`는 비밀번호다. 서버에서 `timeout 5 bash -c '</dev/tcp/<RDS 엔드포인트>/5432' && echo 열림`으로 앱 없이 먼저 확인할 수 있다.

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
