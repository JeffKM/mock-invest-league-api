package com.jeffkm.leaguepush;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** 7주차⑤ 본 API 가 Redis 채널로 보낸 메시지가 SSE 로 연결된 클라이언트에게 event: tick 으로 간다(Netty 로 띄워서). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PushEndToEndTest.RedisConfig.class)
class PushEndToEndTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class RedisConfig {
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
      return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
  }

  @LocalServerPort int port;
  @Autowired ReactiveStringRedisTemplate redis;
  @Autowired ContestStreams streams;

  @Test
  void Redis_채널의_메시지가_SSE_로_간다() {
    Flux<ServerSentEvent<String>> events = WebClient.create("http://localhost:" + port).get()
        .uri("/contests/{id}/stream", 9).retrieve()
        .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
        .filter(e -> "tick".equals(e.event()));

    StepVerifier.create(events.take(1))
        .then(() -> {
          // pub/sub 은 구독이 붙기 전 메시지를 버린다. SSE 연결과 Redis 구독이 모두 붙을 때까지 몇 번 보낸다(take(1) 이라 중복은 상관없다)
          for (int i = 0; i < 50 && streams.connections() == 0; i++) {
            sleep(100);
          }
          for (int i = 0; i < 20; i++) {
            redis.convertAndSend(RedisRelay.CHANNEL_PREFIX + 9, "{\"tick\":7}").block();
            sleep(100);
          }
        })
        .assertNext(e -> assertThat(e.data()).isEqualTo("{\"tick\":7}"))
        .expectComplete()
        .verify(Duration.ofSeconds(10));
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
