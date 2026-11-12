package com.jeffkm.mockleague.contest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** I9: 주문은 RUNNING 대회의 장 시간 [09:00, 15:30) 안에서만 받는다. */
class MarketHoursTest {

  private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

  private Contest running() {
    Contest c = Contest.withDefaults("장 시간 테스트", DAY, LocalDate.of(2026, 12, 31), 42L);
    c.start();
    return c;
  }

  private static ErrorCode codeOf(Throwable e) {
    return ((BusinessException) e).errorCode();
  }

  @Test
  void I9_개장_정각은_열려_있고_첫_틱이다() {
    assertThat(running().openTickAt(DAY.atTime(9, 0))).isZero();
  }

  @Test
  void 틱은_5분마다_하나씩_늘어난다() {
    Contest c = running();
    assertThat(c.openTickAt(DAY.atTime(9, 4, 59))).isZero();
    assertThat(c.openTickAt(DAY.atTime(9, 5))).isEqualTo(1);
    assertThat(c.openTickAt(DAY.atTime(15, 29))).isEqualTo(77); // 하루 78틱(0~77)
  }

  @Test
  void I9_마감_정각과_개장_전은_닫혀_있다() {
    Contest c = running();
    for (LocalDateTime t : new LocalDateTime[] {DAY.atTime(15, 30), DAY.atTime(8, 59, 59)}) {
      assertThatThrownBy(() -> c.openTickAt(t))
          .extracting(MarketHoursTest::codeOf)
          .isEqualTo(ErrorCode.MARKET_CLOSED);
    }
  }

  @Test
  void I9_대회_기간_밖의_날짜는_닫혀_있다() {
    Contest c = running();
    assertThatThrownBy(() -> c.openTickAt(LocalDate.of(2026, 11, 30).atTime(10, 0)))
        .extracting(MarketHoursTest::codeOf)
        .isEqualTo(ErrorCode.MARKET_CLOSED);
    assertThatThrownBy(() -> c.openTickAt(LocalDate.of(2027, 1, 1).atTime(10, 0)))
        .extracting(MarketHoursTest::codeOf)
        .isEqualTo(ErrorCode.MARKET_CLOSED);
  }

  @Test
  void I9_진행_중이_아닌_대회는_장_시간이어도_주문을_받지_않는다() {
    Contest scheduled = Contest.withDefaults("시작 전", DAY, LocalDate.of(2026, 12, 31), 42L);
    assertThatThrownBy(() -> scheduled.openTickAt(DAY.atTime(10, 0)))
        .extracting(MarketHoursTest::codeOf)
        .isEqualTo(ErrorCode.CONTEST_NOT_RUNNING);
  }
}
