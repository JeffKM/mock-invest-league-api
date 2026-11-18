# 앱 이미지(5주차). jar 는 밖에서 만든다: ./gradlew bootJar → docker build -t mock-invest-league-api .
# JDK 로 이미지 안에서 빌드하지 않는 이유: 빌드는 CI·로컬의 Gradle 캐시를 쓰는 편이 빠르고, 실행 이미지에는 JRE 만 있으면 된다.

# 1단계: jar 를 계층(layer)별 폴더로 푼다. 자주 안 바뀌는 라이브러리와 자주 바뀌는 내 코드를 나누기 위함
FROM eclipse-temurin:17-jre-alpine AS extract
WORKDIR /work
ARG JAR=build/libs/mock-invest-league-api-0.0.1-SNAPSHOT.jar
COPY ${JAR} app.jar
# 6주차⑬ P10: --launcher(JarLauncher 로 띄우는 풀린 폴더) 대신 jar + lib/ 로 푼다. 클래스 공유(CDS)는 jar 에서 읽은 클래스만 저장한다
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# 2단계: 실행 이미지. 바뀌지 않는 계층부터 복사한다 → 코드만 고치면 마지막 계층(수백 KB)만 새로 만들고 올린다
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app   # root 가 아닌 사용자로 실행
WORKDIR /app
COPY --from=extract /work/extracted/dependencies/ ./
COPY --from=extract /work/extracted/spring-boot-loader/ ./
COPY --from=extract /work/extracted/snapshot-dependencies/ ./
COPY --from=extract /work/extracted/application/ ./
# 6주차⑬ P10 클래스 공유 저장(AppCDS). 트래픽 중 배포하면 새 앱이 뜨는 20초 동안 끊겼다. 그 시간 대부분은 클래스를 읽고 초기화하는 일이다.
# 이미지를 만들 때 한 번 띄워 컨텍스트가 준비되는 순간 끝내고(spring.context.exit=onRefresh), 그동안 읽은 클래스를 app.jsa 에 저장한다.
# 실행할 때 그 파일을 쓰면 클래스를 다시 읽고 검증하는 일을 건너뛴다. CI 에는 DB 가 없으므로 이 한 번은 DB 에 붙지 않게 한다
# (Flyway 끔, 스키마 검사(ddl-auto validate) 끔, Hibernate 가 DB 메타데이터를 묻지 않게). 운영 기동은 그대로 검사한다. 비밀값 자리는 이 줄에서만 쓰는 가짜 값이다(이미지 ENV 에 남지 않는다)
RUN SPRING_PROFILES_ACTIVE=prod DB_URL=jdbc:postgresql://localhost:5432/cds DB_USERNAME=cds DB_PASSWORD=cds ADMIN_TOKEN=cds-training \
    java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect \
      -jar app.jar > /tmp/cds-training.log 2>&1; \
    if grep -q 'Application run failed' /tmp/cds-training.log; then tail -20 /tmp/cds-training.log; exit 1; fi; \
    rm /tmp/cds-training.log; ls -l app.jsa
USER app
# 힙은 컨테이너 메모리 한도의 40%(700MB → 약 280MB). 시간대는 로그용(업무 날짜는 코드가 Asia/Seoul 을 직접 쓴다)
# 6주차⑩ P7: 처음엔 75%(525MB)였다. 힙 밖(클래스·코드 캐시 약 188MB)과 스레드 200개 몫을 더하면 앱이 760MB 까지 커졌다.
# 서버(913MB)에서 dockerd·containerd 등이 약 130MB 를 쓰므로 앱 몫은 600MB 안팎이다. 넘친 앱 메모리가 스왑으로 밀렸고,
# GC 가 스왑된 힙을 훑느라 17~27초 멈췄다(CPU 는 거의 놀면서). 50%(355MB)에서 Full GC 최대 1.36초, 40% 에서 0.31초(docs/07 13절).
# GC 뒤 살아남는 힙은 100MB 안팎이라 280MB 로도 넉넉하다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -Duser.timezone=Asia/Seoul"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]
