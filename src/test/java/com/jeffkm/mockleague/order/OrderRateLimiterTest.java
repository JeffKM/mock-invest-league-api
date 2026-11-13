package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 토큰 버킷 규칙만 본다. 시간은 가짜 시계(나노초)로 직접 움직인다. */
class OrderRateLimiterTest {

  private final AtomicLong now = new AtomicLong(0);
  private final OrderRateLimiter limiter = new OrderRateLimiter(5, 10, now::get); // 초당 5건, 한 번에 10건

  @Test
  void 처음에는_burst_만큼_한꺼번에_받고_그다음은_거절한다() {
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.tryAcquire(1L)).as("%d번째", i + 1).isTrue();
    }
    assertThat(limiter.tryAcquire(1L)).isFalse();
  }

  @Test
  void 시간이_지나면_초당_perSecond_개씩_다시_찬다() {
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire(1L);
    }
    now.addAndGet(200_000_000L); // 0.2초 → 1개
    assertThat(limiter.tryAcquire(1L)).isTrue();
    assertThat(limiter.tryAcquire(1L)).isFalse();

    now.addAndGet(1_000_000_000L); // 1초 → 5개
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire(1L)).isTrue();
    }
    assertThat(limiter.tryAcquire(1L)).isFalse();
  }

  @Test
  void 오래_쉬어도_burst_보다_많이_쌓이지_않는다() {
    now.addAndGet(3_600_000_000_000L); // 1시간
    int accepted = 0;
    while (limiter.tryAcquire(1L)) {
      accepted++;
    }
    assertThat(accepted).isEqualTo(10);
  }

  @Test
  void 사용자끼리는_서로_영향을_주지_않는다() {
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire(1L);
    }
    assertThat(limiter.tryAcquire(1L)).isFalse();
    assertThat(limiter.tryAcquire(2L)).isTrue();
  }

  @Test
  void 한동안_주문이_없던_사용자의_기록은_지운다() {
    for (long user = 0; user < 9_999; user++) {
      limiter.tryAcquire(user);
    }
    now.addAndGet(61_000_000_000L); // 61초 뒤
    limiter.tryAcquire(100_000L);   // 1만 번째 호출에서 청소
    assertThat(limiter.trackedUsers()).isEqualTo(1);
  }

  @Test
  void 잘못된_설정은_시작할_때_막는다() {
    assertThatThrownBy(() -> new OrderRateLimiter(0, 10, now::get)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderRateLimiter(5, 0, now::get)).isInstanceOf(IllegalArgumentException.class);
  }
}
