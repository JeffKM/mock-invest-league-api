package com.jeffkm.mockleague.market;

import java.time.LocalDate;
import java.util.Map;

/**
 * 가격을 알려 주는 창구. 주문·계좌 서비스는 가격이 "어디서 오는지" 모른다.
 * 1주차에는 상장 기준가 구현이었고, 3주차에 틱 가격 구현(TickPriceProvider)으로 바꿨다.
 */
public interface PriceProvider {

  /**
   * 정확히 그 틱의 가격(주문 체결용). 주문 서비스가 이미 정한 (날짜, 틱)을 넘긴다.
   * 가격이 "지금 시각"을 따로 읽으면, 서비스가 본 틱과 가격의 틱이 경계에서 어긋날 수 있다.
   * 상장되지 않은 종목이면 NOT_FOUND, 가격이 아직 없으면 PRICE_NOT_READY.
   */
  long priceAt(Long contestId, Long companyId, LocalDate day, int tick);

  /** 대회 전체 종목의 "지금 보여 줄" 가격(companyId → 가격). 미래 가격은 섞이지 않는다(I11). */
  Map<Long, Long> currentPrices(Long contestId);
}
