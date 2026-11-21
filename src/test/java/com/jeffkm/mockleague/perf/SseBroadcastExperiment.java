package com.jeffkm.mockleague.perf;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 7주차⑤ 시세 푸시를 Tomcat(Spring MVC SseEmitter)으로 만들면 무엇이 깨지는가. 같은 조건의 Netty 판은 SseNettyExperiment.
 * 서버는 DB 없이 SSE 만 띄운다(운영과 같은 스레드 50).
 * 실행: ulimit -n 10240; LEAGUE_EXPERIMENT=true ./run_tests.sh --tests '*SseBroadcastExperiment*'   결과: build/experiments/
 */
@EnabledIfEnvironmentVariable(named = "LEAGUE_EXPERIMENT", matches = "true")
@SpringBootTest(classes = SseBroadcastExperiment.App.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
        "spring.main.web-application-type=servlet",
        "server.port=18090", "server.tomcat.threads.max=50", "server.tomcat.max-connections=20000",
        "server.tomcat.accept-count=1024", "spring.mvc.async.request-timeout=-1",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"})
class SseBroadcastExperiment {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(TomcatHub.class)
  static class App {
  }

  @RestController
  static class TomcatHub implements SseExperimentRunner.Hub {
    final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe() {
      SseEmitter e = new SseEmitter(3_600_000L);
      emitters.add(e);
      e.onCompletion(() -> emitters.remove(e));
      e.onError(x -> emitters.remove(e));
      return e;
    }

    /** 흔한 구현: 연결 목록을 돌며 하나씩 보낸다. send 는 소켓에 블로킹으로 쓴다. 실패한 연결은 빼낸다. */
    @Override
    public double broadcast(String data) {
      long t = System.nanoTime();
      for (SseEmitter e : emitters) {
        try {
          e.send(SseEmitter.event().data(data, MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException x) {
          emitters.remove(e);
        }
      }
      return (System.nanoTime() - t) / 1e6;
    }

    @Override
    public int subscribers() {
      return emitters.size();
    }

    @Override
    public void closeAll() {
      emitters.forEach(SseEmitter::complete);
      emitters.clear();
    }
  }

  @Autowired TomcatHub hub;

  @Test
  void 느린_연결이_없을_때와_있을_때() throws Exception {
    SseExperimentRunner.compare("tomcat", 18090, hub);
  }
}
