package com.jeffkm.leaguepush;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 대회별 방송 채널. 방송은 sink 에 한 번 넣는 것으로 끝나고, 소켓 쓰기는 Netty 가 연결마다 논블로킹으로 한다.
 *
 * <ul>
 *   <li><b>느린 연결.</b> 못 받아 가는 연결은 그 연결의 버퍼(기본 최근 16개)만 차고, 넘치면 오래된 것부터 버린다.
 *       시세는 최신 값만 의미가 있다. 다른 연결은 영향을 받지 않는다(Tomcat SseEmitter 는 여기서 방송 전체가 60초씩 막혔다).</li>
 *   <li><b>연결 수 상한.</b> 넘으면 받지 않는다(503). 메모리가 연결 수에 비례하므로, 서버가 죽기 전에 거절한다.</li>
 *   <li><b>마지막 메시지.</b> 새로 붙은 클라이언트는 다음 틱(최대 5분)을 기다리지 않고 마지막 시세를 바로 받는다.</li>
 * </ul>
 */
@Component
public class ContestStreams {

  /** 연결 수 상한을 넘었을 때. 컨트롤러가 503 으로 바꾼다. */
  public static class TooManyConnections extends RuntimeException {
    TooManyConnections() {
      super(null, null, false, false);
    }
  }

  private static final class Stream {
    final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
    final AtomicReference<String> latest = new AtomicReference<>();
  }

  private final Map<Long, Stream> streams = new ConcurrentHashMap<>();
  private final AtomicInteger connections = new AtomicInteger();
  private final int maxConnections;
  private final int buffer;
  private final Counter published;
  private final Counter dropped;
  private final Counter rejected;

  public ContestStreams(@Value("${league.push.max-connections:10000}") int maxConnections,
      @Value("${league.push.buffer:16}") int buffer, MeterRegistry meters) {
    this.maxConnections = maxConnections;
    this.buffer = buffer;
    this.published = meters.counter("push.messages", "result", "published");
    this.dropped = meters.counter("push.messages", "result", "dropped");
    this.rejected = meters.counter("push.connections.rejected");
    meters.gauge("push.connections", connections);
  }

  /** 그 대회를 듣는 모든 연결에 보낸다. 바로 돌아온다. */
  public void publish(long contestId, String message) {
    Stream s = streams.computeIfAbsent(contestId, id -> new Stream());
    s.latest.set(message);
    synchronized (s) {   // sink 는 동시에 두 스레드가 넣으면 실패한다. 받는 쪽은 Redis 구독 하나지만 막아 둔다
      s.sink.tryEmitNext(message);
    }
    published.increment();
  }

  /** 그 대회의 방송을 듣는다. 연결이 끝나면(정상·끊김·오류) 연결 수를 돌려놓는다. */
  public Flux<String> subscribe(long contestId) {
    if (connections.incrementAndGet() > maxConnections) {
      connections.decrementAndGet();
      rejected.increment();
      throw new TooManyConnections();
    }
    Stream s = streams.computeIfAbsent(contestId, id -> new Stream());
    Flux<String> live = s.sink.asFlux()
        .onBackpressureBuffer(buffer, message -> dropped.increment(), BufferOverflowStrategy.DROP_OLDEST);
    // shortcut: 마지막 메시지를 읽은 뒤 구독이 붙기 전 사이에 온 메시지 하나는 놓칠 수 있다. 다음 틱에 새로 오므로 둔다
    String last = s.latest.get();
    return (last == null ? live : live.startWith(last))
        .doFinally(signal -> connections.decrementAndGet());
  }

  public int connections() {
    return connections.get();
  }
}
