package com.jeffkm.mockleague.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.company.Sector;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 공시 계획 단위 테스트(F6). */
class DisclosurePlannerTest {

  private static final LocalDate DAY = LocalDate.of(2026, 12, 1);
  private static final int TICKS = 78;

  private static final List<DisclosurePlanner.Listed> LISTED = List.of(
      new DisclosurePlanner.Listed(1L, "SBSI", "솔빛실리콘", Sector.SEMI_IT),
      new DisclosurePlanner.Listed(2L, "ARCL", "아라클라우드", Sector.SEMI_IT),
      new DisclosurePlanner.Listed(3L, "PRCB", "푸른세포바이오", Sector.BIO),
      new DisclosurePlanner.Listed(4L, "DDBK", "든든은행", Sector.FINANCE));

  @Test
  void 같은_시드와_날짜면_같은_공시이고_상장_목록_순서와_무관하다() {
    List<DisclosurePlanner.Listed> shuffled = new ArrayList<>(LISTED);
    Collections.reverse(shuffled);
    for (int d = 0; d < 50; d++) {
      assertThat(DisclosurePlanner.planDay(42L, DAY.plusDays(d), TICKS, shuffled))
          .isEqualTo(DisclosurePlanner.planDay(42L, DAY.plusDays(d), TICKS, LISTED));
    }
  }

  @Test
  void 하루_0에서_2건_평균_1건이고_0번_틱에는_없다() {
    int total = 0;
    int days = 2_000;
    for (int d = 0; d < days; d++) {
      List<DisclosurePlanner.Planned> plans = DisclosurePlanner.planDay(7L, DAY.plusDays(d), TICKS, LISTED);
      assertThat(plans.size()).isBetween(0, 2);
      for (DisclosurePlanner.Planned p : plans) {
        assertThat(p.tick()).isBetween(1, TICKS - 1);
        assertThat(p.title()).isNotBlank();
        switch (p.scope()) {
          case COMPANY -> {
            assertThat(p.companyId()).isNotNull();
            assertThat(p.sector()).isNull();
            assertThat(Math.abs(p.impactBp())).isBetween(300, 1_200);
          }
          case SECTOR -> {
            assertThat(p.companyId()).isNull();
            assertThat(p.sector()).isNotNull();
            assertThat(Math.abs(p.impactBp())).isBetween(200, 700);
          }
          case MARKET -> {
            assertThat(p.companyId()).isNull();
            assertThat(p.sector()).isNull();
            assertThat(Math.abs(p.impactBp())).isBetween(100, 300);
          }
        }
      }
      total += plans.size();
    }
    assertThat((double) total / days).isBetween(0.9, 1.1);
  }

  @Test
  void 섹터_공시는_그_섹터_종목만_시장_공시는_모두에게_충격을_준다() {
    List<DisclosurePlanner.Planned> plans = List.of(
        new DisclosurePlanner.Planned(0, 10, DisclosureScope.SECTOR, null, Sector.SEMI_IT, "메모리 가격 반등 전망", 500),
        new DisclosurePlanner.Planned(1, 10, DisclosureScope.MARKET, null, null, "기준 금리 동결", 100),
        new DisclosurePlanner.Planned(2, 20, DisclosureScope.COMPANY, 3L, null, "푸른세포바이오, 대형 공급 계약 체결", -400));

    int[] sbsi = DisclosurePlanner.shocksFor(LISTED.get(0), plans, TICKS);
    int[] prcb = DisclosurePlanner.shocksFor(LISTED.get(2), plans, TICKS);
    int[] ddbk = DisclosurePlanner.shocksFor(LISTED.get(3), plans, TICKS);

    assertThat(sbsi[10]).isEqualTo(600);   // 섹터 + 시장이 같은 틱에 겹치면 더한다
    assertThat(prcb[10]).isEqualTo(100);
    assertThat(prcb[20]).isEqualTo(-400);
    assertThat(ddbk[10]).isEqualTo(100);
    assertThat(ddbk[20]).isZero();
    assertThat(DisclosurePlanner.shocksFor(LISTED.get(3), plans.subList(0, 1), TICKS)).isNull(); // 충격 없음
  }
}
