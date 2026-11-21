package com.jeffkm.leaguepush;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * GET /contests/{contestId}/stream — 틱마다 event: tick 으로 시세·순위 JSON 을 보낸다.
 * 사이사이 주석 줄(keepalive)을 보낸다: 중간 프록시가 조용한 연결을 끊지 않게 하고, 죽은 연결은 쓰기 실패로 빨리 정리된다.
 */
@RestController
public class StreamController {

  private final ContestStreams streams;
  private final Duration heartbeat;

  public StreamController(ContestStreams streams, @Value("${league.push.heartbeat:15s}") Duration heartbeat) {
    this.streams = streams;
    this.heartbeat = heartbeat;
  }

  @GetMapping(path = "/contests/{contestId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> stream(@PathVariable long contestId) {
    Flux<ServerSentEvent<String>> ticks = streams.subscribe(contestId)
        .map(message -> ServerSentEvent.builder(message).event("tick").build());
    // 못 받아 가는 연결에는 keepalive 를 쌓지 않고 버린다(interval 은 받는 쪽이 늦으면 오류를 낸다)
    Flux<ServerSentEvent<String>> beats = Flux.interval(heartbeat).onBackpressureDrop()
        .map(i -> ServerSentEvent.<String>builder().comment("keepalive").build());
    return Flux.merge(ticks, beats);
  }

  @ExceptionHandler(ContestStreams.TooManyConnections.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public void tooMany() {
    // 본문 없이 503. 클라이언트(EventSource)는 잠시 뒤 다시 붙는다
  }
}
