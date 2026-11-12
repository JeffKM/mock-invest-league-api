package com.jeffkm.mockleague.account.dto;

import java.util.List;

/**
 * 내 계좌. totalAssets = cash + reservedCash + holdingsValue (F12 순위 기준과 같은 식).
 * 평가액·손익은 현재가 기준이며 수수료는 반영하지 않는다(실제로 팔 때 붙는다).
 */
public record AccountResponse(
    Long participantId,
    long cash,
    long reservedCash,
    long holdingsValue,
    long totalAssets,
    List<Position> holdings) {

  public record Position(
      String ticker,
      String name,
      long quantity,
      long averagePrice,
      long currentPrice,
      long marketValue,
      long unrealizedProfit) {}
}
