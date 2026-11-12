package com.jeffkm.mockleague.ranking.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 순위표. asOf 는 "어느 틱의 가격으로 평가했는가"(그 틱이 시작한 시각)다. 스냅샷이 아직 없으면 날짜·틱이 null, entries 는 빈 목록.
 * 수익률은 bp(1bp = 0.01%) 정수로 준다. 금액처럼 소수 없이 다루기 위함(1234 → 12.34%).
 */
public record RankingResponse(
    LocalDate tradingDay,
    Integer tickNo,
    LocalDateTime asOf,
    int totalParticipants,
    List<Entry> entries) {

  public record Entry(int rank, String nickname, long totalAssets, long profit, long returnBp) {}
}
