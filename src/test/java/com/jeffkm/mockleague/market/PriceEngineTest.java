package com.jeffkm.mockleague.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.company.Volatility;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 가격 엔진 단위 테스트. DB 없이 수천 번 돌린다. */
class PriceEngineTest {

  private static final LocalDate DAY = LocalDate.of(2026, 12, 1);
  private static final int TICKS = 78;
  private static final int LIMIT_BP = 3_000; // ±30%

  @Test
  void 같은_입력이면_언제나_같은_경로다() {
    long[] a = PriceEngine.generateDay(42L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, LIMIT_BP);
    long[] b = PriceEngine.generateDay(42L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, LIMIT_BP);
    assertThat(a).containsExactly(b);
  }

  @Test
  void 시드나_종목이나_날짜가_다르면_경로가_다르다() {
    long[] base = PriceEngine.generateDay(42L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, LIMIT_BP);
    assertThat(PriceEngine.generateDay(43L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, LIMIT_BP))
        .isNotEqualTo(base);
    assertThat(PriceEngine.generateDay(42L, "ARCL", DAY, 84_000, Volatility.MID, TICKS, LIMIT_BP))
        .isNotEqualTo(base);
    assertThat(PriceEngine.generateDay(42L, "SBSI", DAY.plusDays(1), 84_000, Volatility.MID, TICKS, LIMIT_BP))
        .isNotEqualTo(base);
  }

  @Test
  void 시가_0번_틱은_전일_종가이고_경로_길이는_틱_수다() {
    long[] p = PriceEngine.generateDay(7L, "SBSI", DAY, 84_000, Volatility.HIGH, TICKS, LIMIT_BP);
    assertThat(p).hasSize(TICKS);
    assertThat(p[0]).isEqualTo(84_000);
  }

  @Test
  void I8_모든_틱은_전일_종가_대비_제한폭_안이다() {
    // 제한폭을 좁게(±1%) 잡고 변동성이 큰 종목으로 1,000번 돌려 벽에 자주 닿게 만든다
    long prev = 10_000;
    for (long seed = 0; seed < 1_000; seed++) {
      long[] p = PriceEngine.generateDay(seed, "CRGN", DAY, prev, Volatility.HIGH, TICKS, 100);
      assertThat(Arrays.stream(p).min().orElseThrow()).isGreaterThanOrEqualTo(9_900);
      assertThat(Arrays.stream(p).max().orElseThrow()).isLessThanOrEqualTo(10_100);
    }
  }

  @Test
  void I8_싼_종목도_가격은_1원_아래로_내려가지_않는다() {
    for (long seed = 0; seed < 200; seed++) {
      long[] p = PriceEngine.generateDay(seed, "IECP", DAY, 2, Volatility.HIGH, TICKS, 10_000);
      assertThat(Arrays.stream(p).min().orElseThrow()).isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  void 몬테카를로_하루_수익률의_표준편차가_변동성_등급과_맞는다() {
    // 2,000일을 만들어 "하루 로그 수익률 = ln(종가/전일 종가)"의 표준편차를 잰다.
    // 0번 틱은 움직이지 않으므로 기대값은 σ × √(77/78). 표본 오차를 감안해 ±10% 안이면 통과
    for (Volatility v : Volatility.values()) {
      int n = 2_000;
      double sum = 0;
      double sumSq = 0;
      for (int d = 0; d < n; d++) {
        long[] p = PriceEngine.generateDay(99L, "TEST", DAY.plusDays(d), 100_000, v, TICKS, LIMIT_BP);
        double r = Math.log(p[TICKS - 1] / 100_000.0);
        sum += r;
        sumSq += r * r;
      }
      double mean = sum / n;
      double std = Math.sqrt(sumSq / n - mean * mean);
      double expected = v.dailySigma() * Math.sqrt((TICKS - 1) / (double) TICKS);
      assertThat(std).as("%s 표준편차", v).isBetween(expected * 0.9, expected * 1.1);
      assertThat(mean).as("%s 평균은 0 근처(오르지도 내리지도 않는 경로)", v).isBetween(-expected * 0.1, expected * 0.1);
    }
  }
}
