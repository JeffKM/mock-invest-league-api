package com.jeffkm.mockleague.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 덜어내기(6주차⑨)의 자리 세기. 스프링 없이 빠르게. */
class LoadShedderTest {

  @Test
  void 자리가_없으면_기다린_뒤_거절하고_자리를_돌려주면_다시_받는다() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    LoadShedder shedder = new LoadShedder(2, Duration.ofMillis(50), meters);

    assertThat(shedder.tryEnter()).isTrue();
    assertThat(shedder.tryEnter()).isTrue();
    assertThat(meters.get("http.inflight").gauge().value()).isEqualTo(2);

    long started = System.nanoTime();
    assertThat(shedder.tryEnter()).as("자리 2개가 다 찼다").isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).as("거절 전에 max-wait 만큼 기다린다")
        .isGreaterThanOrEqualTo(Duration.ofMillis(40));
    assertThat(meters.get("http.shed").counter().count()).isEqualTo(1);

    shedder.exit();
    assertThat(shedder.tryEnter()).as("하나를 돌려준 뒤").isTrue();
  }

  @Test
  void 기다리는_사이_자리가_나면_거절하지_않는다() throws Exception {
    LoadShedder shedder = new LoadShedder(1, Duration.ofSeconds(2), new SimpleMeterRegistry());
    assertThat(shedder.tryEnter()).isTrue();

    Thread releaser = new Thread(() -> {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      shedder.exit();
    });
    releaser.start();
    assertThat(shedder.tryEnter()).as("100 ms 뒤 자리가 났다").isTrue();
    releaser.join();
  }
}
