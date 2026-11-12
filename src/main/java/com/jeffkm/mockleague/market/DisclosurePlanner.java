package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.company.Sector;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 하루치 가상 공시를 정한다(F6). PriceEngine 처럼 DB·Spring 을 모르는 순수 계산이다.
 *
 * <p>공시는 가격 경로를 만들 때 함께 정한다. 그래야 "공시가 났는데 가격은 그대로" 같은 어긋남이 없다.
 * 같은 (대회 시드, 날짜, 상장 목록) → 언제 돌려도 같은 공시. 가격과 같은 이유로 재실행·서버 여러 대에 안전하다.
 *
 * <p>크기 정하기(몬테카를로로 확인, PriceEngineTest 참고): 하루 표준편차가 1.0~3.5% 이므로
 * <ul>
 *   <li>회사 공시 ±3~12%: 작은 쪽은 변동성 높은 종목에선 잡음에 묻히고, 큰 쪽은 어느 종목이든 눈에 띈다.</li>
 *   <li>섹터 공시 ±2~7%: 한 섹터 4종목이 함께 움직인다.</li>
 *   <li>시장 공시 ±1~3%: 전체가 조금씩 움직인다.</li>
 * </ul>
 * 하루 0~2건(평균 1건). 0번 틱(시가)에는 내지 않는다(시가 = 전일 종가 규칙을 지키기 위함).
 */
public final class DisclosurePlanner {

  /** 공시 계획에 필요한 상장 종목 정보. */
  public record Listed(Long companyId, String ticker, String name, Sector sector) {}

  /** 정해진 공시 한 건. companyId·sector 는 범위에 따라 null 일 수 있다. */
  public record Planned(int seq, int tick, DisclosureScope scope, Long companyId, Sector sector,
      String title, int impactBp) {}

  private static final List<String> COMPANY_UP = List.of(
      "%s, 분기 실적 시장 예상 상회", "%s, 대형 공급 계약 체결", "%s, 자사주 매입 결정");
  private static final List<String> COMPANY_DOWN = List.of(
      "%s, 주요 제품 리콜 발표", "%s, 분기 영업손실 전환", "%s, 신작·신제품 출시 연기");
  private static final Map<Sector, String> SECTOR_UP = Map.of(
      Sector.SEMI_IT, "메모리 가격 반등 전망",
      Sector.BIO, "신약 허가 심사 기간 단축 발표",
      Sector.ENERGY, "신재생 에너지 보조금 확대",
      Sector.CONSUMER, "소비 심리 지수 반등",
      Sector.FINANCE, "금융 규제 완화 발표",
      Sector.ENTER, "해외 콘텐츠 수출 호조");
  private static final Map<Sector, String> SECTOR_DOWN = Map.of(
      Sector.SEMI_IT, "글로벌 IT 투자 축소 우려",
      Sector.BIO, "임상 규제 강화 검토",
      Sector.ENERGY, "정부, 해상 풍력 보조금 축소 검토",
      Sector.CONSUMER, "원재료 가격 급등",
      Sector.FINANCE, "가계 대출 연체율 상승",
      Sector.ENTER, "게임 규제 강화 법안 발의");
  private static final List<String> MARKET_UP = List.of("기준 금리 동결", "기준 금리 인하");
  private static final List<String> MARKET_DOWN = List.of("기준 금리 인상", "해외 증시 급락");

  private DisclosurePlanner() {}

  public static List<Planned> planDay(long contestSeed, LocalDate day, int ticks, List<Listed> listings) {
    if (ticks < 2 || listings.isEmpty()) {
      return List.of();
    }
    // 가격 경로의 난수열과 겹치지 않게 티커 자리에 티커로 쓰일 수 없는 문자열을 넣는다
    Random random = new Random(PriceEngine.seedFor(contestSeed, "#DISCLOSURE", day));
    // 상장 목록 순서가 DB 조회 순서에 따라 바뀌어도 같은 결과가 나오게 티커 순으로 고정한다
    List<Listed> sorted = listings.stream().sorted((a, b) -> a.ticker().compareTo(b.ticker())).toList();
    List<Sector> sectors = sorted.stream().map(Listed::sector).distinct().sorted().toList();

    int count = random.nextInt(3);
    List<Planned> plans = new ArrayList<>(count);
    for (int seq = 0; seq < count; seq++) {
      int tick = 1 + random.nextInt(ticks - 1);
      boolean up = random.nextBoolean();
      int roll = random.nextInt(10);
      if (roll < 6) {
        Listed c = sorted.get(random.nextInt(sorted.size()));
        int bp = 300 + random.nextInt(901);
        String title = pick(random, up ? COMPANY_UP : COMPANY_DOWN).formatted(c.name());
        plans.add(new Planned(seq, tick, DisclosureScope.COMPANY, c.companyId(), null, title, up ? bp : -bp));
      } else if (roll < 9) {
        Sector s = sectors.get(random.nextInt(sectors.size()));
        int bp = 200 + random.nextInt(501);
        String title = (up ? SECTOR_UP : SECTOR_DOWN).get(s);
        plans.add(new Planned(seq, tick, DisclosureScope.SECTOR, null, s, title, up ? bp : -bp));
      } else {
        int bp = 100 + random.nextInt(201);
        String title = pick(random, up ? MARKET_UP : MARKET_DOWN);
        plans.add(new Planned(seq, tick, DisclosureScope.MARKET, null, null, title, up ? bp : -bp));
      }
    }
    return plans;
  }

  /**
   * 한 종목이 그날 받는 충격(틱 번호 → bp). 같은 틱에 여러 공시가 겹치면 더한다(작은 값이라 곱과 거의 같다).
   * 받는 충격이 없으면 null 을 돌려줘 PriceEngine 이 기존 경로를 그대로 만든다.
   */
  public static int[] shocksFor(Listed company, List<Planned> plans, int ticks) {
    int[] shocks = null;
    for (Planned p : plans) {
      boolean hits = switch (p.scope()) {
        case COMPANY -> p.companyId().equals(company.companyId());
        case SECTOR -> p.sector() == company.sector();
        case MARKET -> true;
      };
      if (hits) {
        if (shocks == null) {
          shocks = new int[ticks];
        }
        shocks[p.tick()] += p.impactBp();
      }
    }
    return shocks;
  }

  private static String pick(Random random, List<String> options) {
    return options.get(random.nextInt(options.size()));
  }
}
