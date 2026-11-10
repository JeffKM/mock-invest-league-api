package com.jeffkm.mockleague.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.user.User;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** DB 없이 도메인 규칙만 확인하는 단위 테스트. */
class ParticipantTest {

  private Participant newParticipant() {
    Contest contest = Contest.withDefaults("테스트 대회", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), 42L);
    return Participant.join(contest, new User("tester"));
  }

  @Test
  void 참가하면_대회의_초기_자금을_받는다() {
    Participant p = newParticipant();

    assertThat(p.getCash()).isEqualTo(Contest.DEFAULT_INITIAL_CASH);
    assertThat(p.getReservedCash()).isZero();
  }

  @Test
  void I1_잔고와_같은_금액은_뺄_수_있다() {
    Participant p = newParticipant();

    p.withdraw(Contest.DEFAULT_INITIAL_CASH);

    assertThat(p.getCash()).isZero();
  }

  @Test
  void I1_잔고보다_1원이라도_많이_빼면_거절되고_잔고는_그대로다() {
    Participant p = newParticipant();

    assertThatThrownBy(() -> p.withdraw(Contest.DEFAULT_INITIAL_CASH + 1))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.INSUFFICIENT_CASH);
    assertThat(p.getCash()).isEqualTo(Contest.DEFAULT_INITIAL_CASH);
  }

  @Test
  void 금액이_0이하면_프로그램_오류다() {
    Participant p = newParticipant();

    assertThatThrownBy(() -> p.withdraw(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> p.deposit(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 입금하면_잔고가_늘어난다() {
    Participant p = newParticipant();

    p.deposit(5_000);

    assertThat(p.getCash()).isEqualTo(Contest.DEFAULT_INITIAL_CASH + 5_000);
  }

  @Test
  void 입금으로_long_범위를_넘으면_조용히_음수가_되지_않고_예외다() {
    Participant p = newParticipant();

    assertThatThrownBy(() -> p.deposit(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
  }
}
