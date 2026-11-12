package com.jeffkm.mockleague.market;

import java.util.Map;

/**
 * 현재가를 알려 주는 창구. 주문·계좌 서비스는 가격이 "어디서 오는지" 모른다.
 * 1주차 구현은 상장 기준가(ListingBasePriceProvider), 3주차에 틱 가격 구현으로 바꾼다.
 * 그때 서비스 코드는 고치지 않는다(인터페이스에 의존하므로).
 */
public interface PriceProvider {

  /** 대회에 상장된 한 종목의 현재가. 상장되지 않았으면 NOT_FOUND. */
  long currentPrice(Long contestId, Long companyId);

  /** 대회 전체 종목의 현재가(companyId → 가격). 목록 화면에서 종목마다 따로 묻지 않기 위함. */
  Map<Long, Long> currentPrices(Long contestId);
}
