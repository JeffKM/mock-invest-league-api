package com.jeffkm.leaguepush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;

/** 7주차⑤ 방송 채널: 느린 연결이 다른 연결을 막지 않는다, 연결 수 상한, 새 연결은 마지막 시세를 바로 받는다. */
class ContestStreamsTest {

  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  /** 처음에 n 개만 달라고 하고 그 뒤로는 달라고 하지 않는 연결(못 받아 가는 휴대폰). */
  static class Slow extends BaseSubscriber<String> {
    final List<String> got = new ArrayList<>();

    @Override
    protected void hookOnSubscribe(org.reactivestreams.Subscription s) {
      // 아무것도 달라고 하지 않는다
    }

    @Override
    protected void hookOnNext(String value) {
      got.add(value);
    }
  }

  @Test
  void 못_받아_가는_연결이_있어도_다른_연결은_모두_받고_느린_연결은_최근_것만_남는다() {
    var streams = new ContestStreams(100, 16, meters);
    List<String> fast = new ArrayList<>();
    Disposable normal = streams.subscribe(1).subscribe(fast::add);
    Slow slow = new Slow();
    streams.subscribe(1).subscribe(slow);

    IntStream.range(0, 100).forEach(i -> streams.publish(1, "t" + i));

    assertThat(fast).hasSize(100);
    slow.request(100);
    assertThat(slow.got).hasSize(16).first().isEqualTo("t84");
    assertThat(slow.got).last().isEqualTo("t99");
    assertThat(meters.counter("push.messages", "result", "dropped").count()).isEqualTo(84);
    normal.dispose();
    slow.dispose();
  }

  @Test
  void 연결_수_상한을_넘으면_받지_않고_끊긴_연결은_수에서_빠진다() {
    var streams = new ContestStreams(1, 16, meters);
    Disposable first = streams.subscribe(1).subscribe();

    assertThatThrownBy(() -> streams.subscribe(1)).isInstanceOf(ContestStreams.TooManyConnections.class);
    assertThat(streams.connections()).isEqualTo(1);

    first.dispose();
    assertThat(streams.connections()).isZero();
    streams.subscribe(1).subscribe().dispose();   // 자리가 나면 다시 받는다
  }

  @Test
  void 새로_붙은_연결은_다음_틱을_기다리지_않고_마지막_시세를_먼저_받는다() {
    var streams = new ContestStreams(10, 16, meters);
    streams.publish(1, "t0");
    streams.publish(1, "t1");

    StepVerifier.create(streams.subscribe(1).take(2))
        .expectNext("t1")
        .then(() -> streams.publish(1, "t2"))
        .expectNext("t2")
        .verifyComplete();
  }

  @Test
  void 대회가_다르면_섞이지_않는다() {
    var streams = new ContestStreams(10, 16, meters);
    List<String> one = new ArrayList<>();
    streams.subscribe(1).subscribe(one::add);
    streams.publish(2, "다른 대회");
    streams.publish(1, "내 대회");
    assertThat(one).containsExactly("내 대회");
  }
}
