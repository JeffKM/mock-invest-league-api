package com.jeffkm.mockleague.contest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** F5·I11: "지금 보여 줄 가격"의 위치. 미래 틱은 절대 나오지 않는다. */
class LatestVisibleTickTest {

  private static final LocalDate START = LocalDate.of(2026, 12, 1);
  private static final LocalDate END = LocalDate.of(2026, 12, 31);
  private final Contest contest = Contest.withDefaults("가격 위치", START, END, 42L);

  @Test
  void 하루는_78틱이다() {
    assertThat(contest.ticksPerDay()).isEqualTo(78);
  }

  @Test
  void 장_중에는_지금_틱이다() {
    assertThat(contest.latestVisibleTick(START.plusDays(1).atTime(10, 0)))
        .contains(new TickPosition(START.plusDays(1), 12));
  }

  @Test
  void 장_시작_전에는_전날_종가다() {
    assertThat(contest.latestVisibleTick(START.plusDays(1).atTime(8, 0)))
        .contains(new TickPosition(START, 77));
  }

  @Test
  void 장_마감_후에는_그날_종가다() {
    assertThat(contest.latestVisibleTick(START.atTime(15, 30))).contains(new TickPosition(START, 77));
  }

  @Test
  void 첫날_장_시작_전과_대회_시작_전에는_틱이_없다() {
    assertThat(contest.latestVisibleTick(START.atTime(8, 59))).isEmpty();
    assertThat(contest.latestVisibleTick(START.minusDays(3).atTime(12, 0))).isEmpty();
  }

  @Test
  void 대회가_끝난_뒤에는_마지막_날_종가다() {
    assertThat(contest.latestVisibleTick(END.plusDays(5).atTime(12, 0))).contains(new TickPosition(END, 77));
  }
}
