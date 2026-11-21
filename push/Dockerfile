# 시세 푸시 서버 이미지(7주차⑤). jar 는 밖에서 만든다: ./gradlew :push:bootJar → docker build -t league-push push/
# 본 API 이미지(루트 Dockerfile)와 같은 방식: 계층별로 풀어 바뀌지 않는 라이브러리 계층을 재사용한다
FROM eclipse-temurin:17-jre-alpine AS extract
WORKDIR /work
ARG JAR=build/libs/push-0.0.1-SNAPSHOT.jar
COPY ${JAR} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=extract /work/extracted/dependencies/ ./
COPY --from=extract /work/extracted/spring-boot-loader/ ./
COPY --from=extract /work/extracted/snapshot-dependencies/ ./
COPY --from=extract /work/extracted/application/ ./
USER app
# 힙은 컨테이너 메모리 한도의 50%. 연결 하나가 힙 약 13KB 라 연결 수 상한(PUSH_MAX_CONNECTIONS)은 힙에 맞춰 compose 에서 정한다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -Duser.timezone=Asia/Seoul"
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
