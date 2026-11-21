package com.jeffkm.mockleague.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 7주차② 알림(외부 호출)이 부른 쪽을 붙잡지 않는가. 가짜 Discord 를 JDK HttpServer 로 띄운다.
 * "응답 없는 웹훅" = 요청을 받고 답하지 않는 서버(Discord 장애, 네트워크 블랙홀과 같은 모양).
 */
class AlertDeliveryTest {

  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final ExecutorService executor = Executors.newFixedThreadPool(2);
  final CountDownLatch release = new CountDownLatch(1); // 응답 없는 서버를 테스트 끝에 풀어 준다
  final List<String> bodies = new CopyOnWriteArrayList<>();
  HttpServer server;

  @AfterEach
  void tearDown() {
    release.countDown();
    if (server != null) {
      server.stop(0);
    }
    executor.shutdownNow();
  }

  URI start(boolean respond) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/webhook", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      if (!respond) {
        try {
          release.await(); // 답하지 않는다
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    server.start();
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
  }

  DiscordAlertNotifier notifier(URI uri, Duration timeout, int maxInFlight) {
    return new DiscordAlertNotifier(uri, timeout, maxInFlight, executor, meters);
  }

  double count(String result) {
    return meters.counter("alerts", "result", result).count();
  }

  /** 처음 떠올리기 쉬운 구현: 그 자리에서 보내고 기다린다. JDK HttpClient 의 기본 시간 제한은 없다. */
  static void naiveSend(URI uri, String text) throws Exception {
    HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + text + "\"}")).build(),
        HttpResponse.BodyHandlers.discarding());
  }

  @Test
  void 재현_그_자리에서_보내고_기다리면_응답_없는_웹훅에_스레드가_묶인다() throws Exception {
    URI uri = start(false);
    ExecutorService scheduler = Executors.newSingleThreadExecutor(); // 스케줄러 스레드 하나라고 보면 된다
    try {
      Future<?> job = scheduler.submit(() -> {
        naiveSend(uri, "불변식 위반");
        return null;
      });

      assertThatThrownBy(() -> job.get(3, TimeUnit.SECONDS))
          .as("3초가 지나도 끝나지 않는다. 시간 제한이 없으니 웹훅이 답할 때까지 영원히 기다린다")
          .isInstanceOf(TimeoutException.class);
      job.cancel(true);
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void 비동기로_보내면_웹훅이_멈춰도_바로_돌아오고_시간_제한_뒤_실패로_센다() throws Exception {
    URI uri = start(false);
    var notifier = notifier(uri, Duration.ofMillis(300), 4);

    long t = System.nanoTime();
    notifier.send("불변식 위반");
    long tookMillis = (System.nanoTime() - t) / 1_000_000;

    assertThat(tookMillis).as("부른 쪽이 기다린 시간(ms)").isLessThan(200);
    awaitCount("failed", 1, Duration.ofSeconds(5));
    assertThat(count("sent")).isZero();
  }

  @Test
  void 정상이면_content_JSON_으로_보내고_보낸_것으로_센다() throws Exception {
    URI uri = start(true);
    var notifier = notifier(uri, Duration.ofSeconds(2), 4);

    notifier.send("대회 7 \"현금\" 어긋남\n- I5 1건");

    awaitCount("sent", 1, Duration.ofSeconds(5));
    assertThat(bodies).containsExactly("{\"content\":\"대회 7 \\\"현금\\\" 어긋남\\n- I5 1건\"}");
  }

  @Test
  void 웹훅이_멈춘_동안_쌓이는_알림은_상한을_넘으면_버리고_센다() throws Exception {
    URI uri = start(false);
    var notifier = notifier(uri, Duration.ofSeconds(10), 2);

    for (int i = 0; i < 5; i++) {
      notifier.send("알림 " + i);
    }

    assertThat(count("dropped")).isEqualTo(3);
  }

  @Test
  void 웹훅이_꺼져_있어도_예외_없이_실패로_센다() throws Exception {
    var notifier = notifier(URI.create("http://127.0.0.1:1/webhook"), Duration.ofSeconds(1), 4);

    notifier.send("알림");

    awaitCount("failed", 1, Duration.ofSeconds(5));
  }

  @Test
  void Discord_한도인_2000자를_넘으면_자른다() {
    String cut = DiscordAlertNotifier.truncate("가".repeat(2500));

    assertThat(cut).hasSize(DiscordAlertNotifier.MAX_LENGTH).endsWith("…");
    assertThat(DiscordAlertNotifier.truncate("짧다")).isEqualTo("짧다");
  }

  void awaitCount(String result, double expected, Duration within) throws InterruptedException {
    long deadline = System.nanoTime() + within.toNanos();
    while (count(result) < expected && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(count(result)).as(result).isEqualTo(expected);
  }
}
