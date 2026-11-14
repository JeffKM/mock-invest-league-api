package com.jeffkm.mockleague.common;

import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 콜드 스타트 줄이기(6주차⑥). 앱이 뜬 직후의 JVM 은 코드를 아직 JIT 로 컴파일하지 못해 같은 요청에 CPU 를 더 쓴다.
 * 재배포 1분 43초 뒤의 첫 몰림에서 JIT 가 CPU 2개 중 1.8개를 쓰며 커넥션 대기 101, p95 0.5초가 났다(docs/07 8절).
 *
 * <p>그래서 사용자 요청을 받기 전에 자기 자신에게 조회 요청을 보내 자주 쓰는 코드를 미리 컴파일시킨다.
 * ApplicationRunner 는 "준비됨(readiness)"이 켜지기 전에 돈다. 그동안 /actuator/health 는 OUT_OF_SERVICE 라
 * 도커 헬스 체크와 deploy.sh 는 데우기가 끝난 뒤에야 성공한다.
 *
 * <p>읽기만 한다(계좌·순위·주문 목록·공시). 주문 접수는 쓰기라 데우지 않는다. 진행 중인 대회가 없으면 건너뛴다.
 */
@Component
@ConditionalOnProperty(name = "league.warmup.enabled", havingValue = "true")
public class WarmupRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WarmupRunner.class);
  private static final int THREADS = 4;

  private final WebServerApplicationContext context;
  private final JdbcTemplate jdbc;
  private final Duration duration;

  public WarmupRunner(WebServerApplicationContext context, JdbcTemplate jdbc,
      @Value("${league.warmup.duration:20s}") Duration duration) {
    this.context = context;
    this.jdbc = jdbc;
    this.duration = duration;
  }

  @Override
  public void run(ApplicationArguments args) {
    warmUp();
  }

  /** 데우기 결과. 상태 코드(200, 400 …)별 요청 수. */
  public record Result(long requests, Map<Integer, Long> byStatus, long jitMillis, long elapsedMillis) {}

  public Result warmUp() {
    List<Map<String, Object>> target = jdbc.queryForList("""
        select p.contest_id, p.user_id from participants p join contests c on c.id = p.contest_id
         where c.status = 'RUNNING' order by p.id desc limit 1
        """);
    if (target.isEmpty()) {
      log.info("데우기 건너뜀: 진행 중인 대회가 없다");
      return new Result(0, Map.of(), 0, 0);
    }
    long contestId = ((Number) target.get(0).get("contest_id")).longValue();
    String userId = String.valueOf(target.get(0).get("user_id"));
    String base = "http://localhost:" + context.getWebServer().getPort() + "/api/contests/" + contestId;
    List<String> paths = List.of("/me", "/rankings?limit=20", "/rankings/me", "/orders", "/disclosures?limit=20");

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    Map<Integer, Long> byStatus = new ConcurrentHashMap<>();
    AtomicLong requests = new AtomicLong();
    CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
    long jitBefore = jit.getTotalCompilationTime();
    long start = System.nanoTime();
    long deadline = start + duration.toNanos();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    for (int t = 0; t < THREADS; t++) {
      pool.submit(() -> {
        int i = 0;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
          HttpRequest req = HttpRequest.newBuilder(URI.create(base + paths.get(i++ % paths.size())))
              .header("X-User-Id", userId).timeout(Duration.ofSeconds(5)).GET().build();
          try {
            int status = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            byStatus.merge(status, 1L, Long::sum);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            byStatus.merge(-1, 1L, Long::sum); // 연결 실패 등
          }
          requests.incrementAndGet();
        }
      });
    }
    pool.shutdown();
    try {
      pool.awaitTermination(duration.toMillis() + 10_000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    pool.shutdownNow();

    Result result = new Result(requests.get(), Map.copyOf(byStatus), jit.getTotalCompilationTime() - jitBefore,
        (System.nanoTime() - start) / 1_000_000);
    log.info("데우기 끝: 대회={}, 요청 {}건 {}, {} ms 동안 JIT 컴파일 {} ms", contestId, result.requests(),
        result.byStatus(), result.elapsedMillis(), result.jitMillis());
    return result;
  }
}
