# 앱 이미지(5주차). jar 는 밖에서 만든다: ./gradlew bootJar → docker build -t mock-invest-league-api .
# JDK 로 이미지 안에서 빌드하지 않는 이유: 빌드는 CI·로컬의 Gradle 캐시를 쓰는 편이 빠르고, 실행 이미지에는 JRE 만 있으면 된다.

# 1단계: jar 를 계층(layer)별 폴더로 푼다. 자주 안 바뀌는 라이브러리와 자주 바뀌는 내 코드를 나누기 위함
FROM eclipse-temurin:17-jre-alpine AS extract
WORKDIR /work
ARG JAR=build/libs/mock-invest-league-api-0.0.1-SNAPSHOT.jar
COPY ${JAR} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# 2단계: 실행 이미지. 바뀌지 않는 계층부터 복사한다 → 코드만 고치면 마지막 계층(수백 KB)만 새로 만들고 올린다
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app   # root 가 아닌 사용자로 실행
WORKDIR /app
COPY --from=extract /work/extracted/dependencies/ ./
COPY --from=extract /work/extracted/spring-boot-loader/ ./
COPY --from=extract /work/extracted/snapshot-dependencies/ ./
COPY --from=extract /work/extracted/application/ ./
USER app
# 컨테이너 메모리 한도의 75% 를 힙으로. 시간대는 로그용(업무 날짜는 코드가 Asia/Seoul 을 직접 쓴다)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
