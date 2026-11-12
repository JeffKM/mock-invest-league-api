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

  // ---------- 공시 충격(F6) ----------

  @Test
  void 충격이_없으면_기존_경로와_같다() {
    long[] plain = PriceEngine.generateDay(42L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, LIMIT_BP);
    assertThat(PriceEngine.generateDay(42L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, LIMIT_BP, null))
        .containsExactly(plain);
    assertThat(PriceEngine.generateDay(42L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, LIMIT_BP, new int[TICKS]))
        .containsExactly(plain);
  }

  @Test
  void 충격은_그_틱부터_계단처럼_가격을_옮기고_이전_틱은_그대로다() {
    int[] shock = new int[TICKS];
    shock[30] = 1_000; // +10%
    long[] plain = PriceEngine.generateDay(42L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, 10_000);
    long[] shocked = PriceEngine.generateDay(42L, "SBSI", DAY, 84_000, Volatility.MID, TICKS, 10_000, shock);

    assertThat(Arrays.copyOf(shocked, 30)).containsExactly(Arrays.copyOf(plain, 30));
    // 난수가 같으므로 30번 틱부터는 "같은 경로 × 1.1" 이다(반올림 1원 오차)
    for (int t = 30; t < TICKS; t++) {
      assertThat((double) shocked[t]).isCloseTo(plain[t] * 1.1, org.assertj.core.data.Offset.offset(1.0));
    }
  }

  @Test
  void I8_큰_충격도_제한폭을_넘지_않는다() {
    int[] shock = new int[TICKS];
    shock[10] = 9_000;   // +90%
    shock[50] = -9_000;  // −90%
    for (long seed = 0; seed < 300; seed++) {
      long[] p = PriceEngine.generateDay(seed, "MRSF", DAY, 18_500, Volatility.HIGH, TICKS, LIMIT_BP, shock);
      assertThat(Arrays.stream(p).min().orElseThrow()).isGreaterThanOrEqualTo(12_950L);
      assertThat(Arrays.stream(p).max().orElseThrow()).isLessThanOrEqualTo(24_050L);
    }
  }

  /**
   * 공시 크기 정하기(몬테카를로). 같은 시드로 "충격 있는 경로"와 "없는 경로"를 2,000번씩 만들어
   * (1) 충격이 평균적으로 정확히 그 크기만큼 종가를 옮기는지, (2) 공시 방향대로 마감할 확률이 얼마인지 본다.
   * (2)가 거의 100% 면 공시 = 정답이라 게임이 싱겁고, 50% 근처면 공시가 의미 없다.
   */
  @Test
  void 몬테카를로_공시_크기별로_공시_방향대로_마감할_확률() {
    int n = 2_000;
    int[] impacts = {300, 500, 1_000, 1_200};
    StringBuilder table = new StringBuilder("\n변동성 | 충격 | 평균 이동(로그) | 공시 방향대로 마감할 확률\n");
    for (Volatility v : Volatility.values()) {
      double previous = 0;
      for (int bp : impacts) {
        int[] shock = new int[TICKS];
        shock[39] = bp; // 점심 무렵 공시
        double sumLogRatio = 0;
        int upDays = 0;
        for (long seed = 0; seed < n; seed++) {
          long[] plain = PriceEngine.generateDay(seed, "TEST", DAY, 100_000, v, TICKS, LIMIT_BP);
          long[] shocked = PriceEngine.generateDay(seed, "TEST", DAY, 100_000, v, TICKS, LIMIT_BP, shock);
          sumLogRatio += Math.log((double) shocked[TICKS - 1] / plain[TICKS - 1]);
          if (shocked[TICKS - 1] > 100_000) {
            upDays++;
          }
        }
        double meanShift = sumLogRatio / n;
        double hitRate = (double) upDays / n;
        table.append("%-4s | %4.1f%% | %.4f (기대 %.4f) | %.1f%%%n"
            .formatted(v, bp / 100.0, meanShift, Math.log(1 + bp / 10_000.0), hitRate * 100));

        assertThat(meanShift).as("%s %dbp 평균 이동", v, bp)
            .isCloseTo(Math.log(1 + bp / 10_000.0), org.assertj.core.data.Offset.offset(0.002));
        assertThat(hitRate).as("충격이 클수록 방향대로 마감할 확률이 높다").isGreaterThanOrEqualTo(previous);
        previous = hitRate;
      }
    }
    System.out.println(table);
  }
}
