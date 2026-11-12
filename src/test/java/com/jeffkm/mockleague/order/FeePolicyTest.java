package com.jeffkm.mockleague.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FeePolicyTest {

  @Test
  void I4_보통_금액은_금액_곱하기_수수료율이다() {
    assertThat(FeePolicy.fee(840_000, 50)).isEqualTo(4_200); // 0.5%
  }

  @Test
  void I4_원_미만은_버린다() {
    assertThat(FeePolicy.fee(199, 50)).isZero();   // 0.995원 → 0원
    assertThat(FeePolicy.fee(200, 50)).isEqualTo(1);
    assertThat(FeePolicy.fee(1_999, 50)).isEqualTo(9); // 9.995원 → 9원(반올림이면 10원)
  }

  @Test
  void 금액이_0이면_수수료도_0이다() {
    assertThat(FeePolicy.fee(0, 50)).isZero();
  }

  @Test
  void 곱셈이_long_범위를_넘으면_조용히_틀리지_않고_예외를_던진다() {
    assertThatThrownBy(() -> FeePolicy.fee(Long.MAX_VALUE / 2, 50))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void 수수료율이_범위를_벗어나면_거절한다() {
    assertThatThrownBy(() -> FeePolicy.fee(1_000, -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FeePolicy.fee(1_000, 10_001)).isInstanceOf(IllegalArgumentException.class);
  }
}
