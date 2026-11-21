package com.jeffkm.mockleague.perf;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 7주차⑤ 같은 시세 푸시를 Netty(Spring WebFlux, Reactor Netty)로. 조건은 SseBroadcastExperiment(Tomcat)와 같다.
 * 방송은 공유 sink 에 한 번 넣는 것으로 끝난다. 소켓 쓰기는 Netty 이벤트 루프가 연결마다 논블로킹으로 하고,
 * 소켓이 차서 못 쓰는 연결은 Netty 가 더 달라고 하지 않는다 → 그 연결의 버퍼(최근 16개)만 차고 오래된 것부터 버린다.
 * 시세는 최신 값만 의미가 있어서 버려도 된다. 다른 연결은 영향을 받지 않는다.
 * 실행: ulimit -n 10240; LEAGUE_EXPERIMENT=true ./run_tests.sh --tests '*SseNettyExperiment*'   결과: build/experiments/
 */
@EnabledIfEnvironmentVariable(named = "LEAGUE_EXPERIMENT", matches = "true")
@SpringBootTest(classes = SseNettyExperiment.App.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
        "spring.main.web-application-type=reactive", "server.port=18091", "experiment.server=${SSE_SERVER:netty}",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"})
class SseNettyExperiment {

  static final int PER_CONNECTION_BUFFER = 16;

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(NettyHub.class)
  static class App {
    /**
     * 서버를 Netty 로 못 박는다. 테스트 클래스패스에 Tomcat 도 있어서, 이것 없이 띄우자 WebFlux 가 Tomcat 위에서 떴다
     * (스레드 이름 http-nio-exec 200개로 알아챔). 그 결과는 "WebFlux on Tomcat"으로 따로 남긴다(SSE_SERVER=tomcat).
     */
    @Bean
    @ConditionalOnProperty(name = "experiment.server", havingValue = "netty", matchIfMissing = true)
    NettyReactiveWebServerFactory nettyFactory() {
      return new NettyReactiveWebServerFactory();
    }
  }

  @RestController
  static class NettyHub implements SseExperimentRunner.Hub {
    volatile Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
    final AtomicInteger subscribers = new AtomicInteger();

    @GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> subscribe() {
      return sink.asFlux()
          .onBackpressureBuffer(PER_CONNECTION_BUFFER, dropped -> { }, BufferOverflowStrategy.DROP_OLDEST)
          .doOnSubscribe(s -> subscribers.incrementAndGet())
          .doFinally(s -> subscribers.decrementAndGet());
    }

    @Override
    public double broadcast(String data) {
      long t = System.nanoTime();
      sink.tryEmitNext(data);   // 방송은 한 스레드에서만 부른다(sink 는 동시 호출에 안전하지 않다)
      return (System.nanoTime() - t) / 1e6;
    }

    @Override
    public int subscribers() {
      return subscribers.get();
    }

    @Override
    public void closeAll() {
      Sinks.Many<String> old = sink;
      sink = Sinks.many().multicast().directBestEffort();
      old.tryEmitComplete();
    }
  }

  @Autowired NettyHub hub;

  @Test
  void 느린_연결이_없을_때와_있을_때() throws Exception {
    SseExperimentRunner.compare("webflux-" + System.getenv().getOrDefault("SSE_SERVER", "netty"), 18091, hub);
  }
}
