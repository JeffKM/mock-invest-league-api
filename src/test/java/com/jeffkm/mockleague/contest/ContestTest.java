package com.jeffkm.mockleague.contest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ContestTest {

  private Contest newContest() {
    return Contest.withDefaults("테스트 대회", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), 42L);
  }

  @Test
  void 만들면_예정_상태이고_기본_규칙을_가진다() {
    Contest c = newContest();

    assertThat(c.getStatus()).isEqualTo(ContestStatus.SCHEDULED);
    assertThat(c.getInitialCash()).isEqualTo(10_000_000L);
    assertThat(c.getFeeBp()).isEqualTo(50);
    assertThat(c.getPriceLimitBp()).isEqualTo(3_000);
  }

  @Test
  void 예정_진행_종료_순서로만_바뀐다() {
    Contest c = newContest();

    c.start();
    assertThat(c.isRunning()).isTrue();
    c.finish();
    assertThat(c.getStatus()).isEqualTo(ContestStatus.FINISHED);

    assertThatThrownBy(c::start).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(c::finish).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 시작일이_종료일보다_늦으면_만들_수_없다() {
    assertThatThrownBy(() -> Contest.withDefaults("x", LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 1), 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
