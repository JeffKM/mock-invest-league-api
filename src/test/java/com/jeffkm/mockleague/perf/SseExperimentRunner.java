package com.jeffkm.mockleague.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 7주차⑤ 시세 푸시 실험의 공통 틀. 서버(Tomcat SseEmitter / Netty WebFlux)만 바꿔 끼우고 같은 조건으로 잰다.
 * 클라이언트는 scripts/experiments/sse_clients.py(다른 프로세스, 서버 힙 측정에 섞이지 않게).
 * 환경 변수: SSE_NORMAL(정상 연결, 기본 3000) SSE_SLOW(읽지 않는 연결, 기본 5) SSE_BROADCASTS(기본 120) SSE_INTERVAL_MS(기본 250)
 */
final class SseExperimentRunner {

  /** 서버 쪽 방송기. broadcast 는 방송 한 번에 부른 쪽이 기다린 시간(ms)을 돌려준다. */
  interface Hub {
    double broadcast(String data);

    int subscribers();

    void closeAll();
  }

  static final int NORMAL = env("SSE_NORMAL", 3000);
  static final int SLOW = env("SSE_SLOW", 5);
  static final int BROADCASTS = env("SSE_BROADCASTS", 120);
  static final long INTERVAL_MS = env("SSE_INTERVAL_MS", 250);   // 실제 틱(5분)보다 훨씬 잦다. 느린 연결의 버퍼를 빨리 채우려고 시간을 당긴 것
  static final long WALL_LIMIT_MS = 120_000;
  static final Path OUT = Path.of("build/experiments");

  private SseExperimentRunner() {
  }

  static int env(String name, int def) {
    return Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(def)));
  }

  /** 느린 연결 0개와 SLOW 개로 두 번 재고 build/experiments/sse-<이름>.txt 에 남긴다. */
  static void compare(String name, int port, Hub hub) throws Exception {
    Files.createDirectories(OUT);
    List<String> report = new ArrayList<>();
    report.add(run(name, port, hub, NORMAL, 0));
    report.add(run(name, port, hub, NORMAL, SLOW));
    Files.write(OUT.resolve("sse-" + name + ".txt"), report, StandardCharsets.UTF_8);
    report.forEach(System.out::println);
  }

  static String run(String name, int port, Hub hub, int normal, int slow) throws Exception {
    hub.closeAll();
    TimeUnit.SECONDS.sleep(2);
    long heap0 = heapAfterGc();
    long[] off0 = offHeap();
    int threads0 = ManagementFactory.getThreadMXBean().getThreadCount();

    Path result = OUT.resolve("sse-" + name + "-clients-" + slow + ".json");
    Process clients = new ProcessBuilder("python3", "-I", "scripts/experiments/sse_clients.py",
        String.valueOf(port), String.valueOf(normal), String.valueOf(slow), result.toString())
        .redirectErrorStream(true).redirectOutput(OUT.resolve("sse-" + name + "-clients-" + slow + ".log").toFile()).start();
    try {
      long deadline = System.currentTimeMillis() + 60_000;
      while (hub.subscribers() < normal + slow && System.currentTimeMillis() < deadline) {
        TimeUnit.MILLISECONDS.sleep(200);
      }
      int held = hub.subscribers();
      long heap1 = heapAfterGc();
      long[] off1 = offHeap();
      int threads1 = ManagementFactory.getThreadMXBean().getThreadCount();
      String threadNames = threadGroups();

      // 방송 한 번 ≈ 2KB: 24종목 가격 + 상위 20명 순위 정도의 크기
      String filler = "x".repeat(1900);
      double[] loops = new double[BROADCASTS];
      long start = System.currentTimeMillis();
      int sent = 0;
      for (int i = 0; i < BROADCASTS && System.currentTimeMillis() - start < WALL_LIMIT_MS; i++) {
        long wait = start + i * INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
          TimeUnit.MILLISECONDS.sleep(wait);
        }
        loops[i] = hub.broadcast("{\"id\":" + i + ",\"sentAt\":" + System.currentTimeMillis() + ",\"p\":\"" + filler + "\"}");
        sent++;
      }
      long elapsed = System.currentTimeMillis() - start;
      TimeUnit.SECONDS.sleep(3);   // 마지막 방송이 도착할 시간
      clients.getOutputStream().close();
      clients.waitFor(60, TimeUnit.SECONDS);

      @SuppressWarnings("unchecked")
      Map<String, Object> json = new ObjectMapper().readValue(result.toFile(), Map.class);
      @SuppressWarnings("unchecked")
      Map<String, List<Number>> lat = (Map<String, List<Number>>) json.get("latency");
      List<Double> early = new ArrayList<>();
      List<Double> late = new ArrayList<>();
      long received = 0;
      for (var entry : lat.entrySet()) {
        int id = Integer.parseInt(entry.getKey());
        received += entry.getValue().size();
        for (Number n : entry.getValue()) {
          if (id < sent / 4) {
            early.add(n.doubleValue());
          } else if (id >= sent * 3 / 4) {
            late.add(n.doubleValue());
          }
        }
      }
      return String.format("""
          == [%s] 정상 %d + 느린 %d (서버가 쥔 연결 %d, 클라이언트 실패 %s)
          힙(GC 뒤): %.1f MB → %.1f MB, 연결당 %.1f KB | 스레드 %d → %d
          방송 %d번을 %.1f초에 (계획 %.1f초) | 방송 한 번에 부른 쪽이 기다린 ms: 처음 1/4 %s, 마지막 1/4 %s
          정상 연결이 받은 지연 ms: 처음 1/4 %s | 마지막 1/4 %s | 받은 메시지 %d / 보낸 %d
          스레드(이름별, 연결을 쥔 뒤): %s
          힙 밖: 프로세스 RSS %.1f MB → %.1f MB(연결당 %.1f KB) | JDK 다이렉트 버퍼 %.1f → %.1f MB | Netty 다이렉트 %.1f → %.1f MB
          """, name, normal, slow, held, json.get("failed"),
          heap0 / 1e6, heap1 / 1e6, held == 0 ? 0 : (heap1 - heap0) / 1024.0 / held, threads0, threads1,
          sent, elapsed / 1000.0, BROADCASTS * INTERVAL_MS / 1000.0,
          stats(slice(loops, 0, sent / 4)), stats(slice(loops, sent * 3 / 4, sent)),
          stats(early), stats(late), received, (long) sent * normal, threadNames,
          off0[0] / 1e6, off1[0] / 1e6, held == 0 ? 0 : (off1[0] - off0[0]) / 1024.0 / held,
          off0[1] / 1e6, off1[1] / 1e6, off0[2] / 1e6, off1[2] / 1e6);
    } finally {
      clients.destroyForcibly();
      hub.closeAll();
    }
  }

  /** 스레드 이름에서 숫자를 뺀 묶음별 개수. 많은 순 8개. */
  static String threadGroups() {
    Map<String, Integer> groups = new java.util.TreeMap<>();
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      groups.merge(t.getName().replaceAll("\\d+", "#"), 1, Integer::sum);
    }
    return groups.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
        .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
  }

  /**
   * 힙만 비교하면 Netty 가 유리하게 보일 수 있다(Netty 는 힙 밖 다이렉트 버퍼를 풀로 쓴다). 그래서 힙 밖도 잰다.
   * [0] 프로세스 RSS(ps), [1] JDK 가 아는 다이렉트 버퍼, [2] Netty 가 따로 세는 다이렉트 메모리(없으면 0)
   */
  static long[] offHeap() throws Exception {
    long pid = ProcessHandle.current().pid();
    Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
    long rss = Long.parseLong(new String(ps.getInputStream().readAllBytes()).trim()) * 1024;
    long direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
        .filter(b -> b.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
    long netty = 0;
    try {
      netty = (long) Class.forName("io.netty.util.internal.PlatformDependent").getMethod("usedDirectMemory").invoke(null);
    } catch (ReflectiveOperationException e) {
      // Netty 가 없는 경우
    }
    return new long[] {rss, direct, Math.max(netty, 0)};
  }

  static long heapAfterGc() throws InterruptedException {
    for (int i = 0; i < 3; i++) {
      System.gc();
      TimeUnit.MILLISECONDS.sleep(300);
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  static List<Double> slice(double[] a, int from, int to) {
    List<Double> r = new ArrayList<>();
    for (int i = from; i < to; i++) {
      r.add(a[i]);
    }
    return r;
  }

  static String stats(List<Double> v) {
    if (v.isEmpty()) {
      return "-";
    }
    List<Double> s = new ArrayList<>(v);
    Collections.sort(s);
    return String.format("p50 %.0f, p99 %.0f, 최대 %.0f", s.get(s.size() / 2),
        s.get(Math.min(s.size() - 1, (int) (s.size() * 0.99))), s.get(s.size() - 1));
  }
}
