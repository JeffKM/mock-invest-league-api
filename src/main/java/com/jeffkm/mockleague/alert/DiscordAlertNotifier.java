package com.jeffkm.mockleague.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discord 웹훅으로 알린다(7주차②). 알림은 부가 기능이라, 알림 때문에 본업(배치·정산)이 늦어지면 안 된다.
 *
 * <ul>
 *   <li><b>비동기.</b> HttpClient.sendAsync 로 보내고 결과를 기다리지 않는다. 부른 스레드(스케줄러)는 바로 돌아간다.</li>
 *   <li><b>시간 제한.</b> 연결·응답 모두 제한을 둔다. JDK HttpClient 의 기본값은 "무한"이라, 응답 없는 서버를 만나면
 *       그 요청은 영원히 남는다(AlertDeliveryTest 가 재현한다).</li>
 *   <li><b>동시에 보내는 수의 상한.</b> 웹훅이 멈춘 동안 알림이 쌓이면 연결과 메모리가 계속 늘어난다.
 *       상한을 넘는 알림은 버리고 세기만 한다(알림 하나를 잃는 것이 서버를 잃는 것보다 낫다).</li>
 *   <li><b>재시도 없음.</b> 같은 경보는 다음 검사 때 다시 나온다. 재시도는 멈춘 웹훅에 요청을 더 쌓을 뿐이다.</li>
 * </ul>
 *
 * <p>웹훅 주소는 비밀값이라 로그에 찍지 않는다.
 */
public class DiscordAlertNotifier implements AlertNotifier {

  private static final Logger log = LoggerFactory.getLogger(DiscordAlertNotifier.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Discord 메시지 content 의 최대 길이. */
  static final int MAX_LENGTH = 2000;

  private final URI webhook;
  private final HttpClient http;
  private final Duration timeout;
  private final Semaphore inFlight;
  private final Counter sent;
  private final Counter failed;
  private final Counter dropped;

  public DiscordAlertNotifier(URI webhook, Duration timeout, int maxInFlight, Executor executor, MeterRegistry meters) {
    this.webhook = webhook;
    this.timeout = timeout;
    this.inFlight = new Semaphore(maxInFlight);
    this.http = HttpClient.newBuilder().connectTimeout(timeout).executor(executor).build();
    this.sent = meters.counter("alerts", "result", "sent");
    this.failed = meters.counter("alerts", "result", "failed");
    this.dropped = meters.counter("alerts", "result", "dropped");
  }

  @Override
  public void send(String text) {
    if (!inFlight.tryAcquire()) {
      dropped.increment();
      return;
    }
    HttpRequest request;
    try {
      request = HttpRequest.newBuilder(webhook)
          .timeout(timeout)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("content", truncate(text)))))
          .build();
    } catch (JsonProcessingException | RuntimeException e) {
      inFlight.release();
      failed.increment();
      log.warn("알림 만들기 실패: {}", e.getClass().getSimpleName());
      return;
    }
    try {
      http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> done(response, error));
    } catch (RuntimeException e) {
      done(null, e);
    }
  }

  private void done(HttpResponse<Void> response, Throwable error) {
    inFlight.release();
    if (error == null && response.statusCode() / 100 == 2) {
      sent.increment();
    } else {
      failed.increment();
      // 스택 트레이스 없이 한 줄만(로그가 병목이 되지 않게, ROADMAP 21단계)
      log.warn("알림 전송 실패: {}", error != null ? error.getClass().getSimpleName() : "HTTP " + response.statusCode());
    }
  }

  static String truncate(String text) {
    return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + "…";
  }
}
