package com.jeffkm.mockleague.contest.dto;

import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestStatus;
import java.time.LocalDate;
import java.time.LocalTime;

public record ContestResponse(
    Long id,
    String name,
    ContestStatus status,
    LocalDate startsOn,
    LocalDate endsOn,
    LocalTime openTime,
    LocalTime closeTime,
    long initialCash,
    int feeBp,
    int listedCount) {

  public static ContestResponse from(Contest c, int listedCount) {
    return new ContestResponse(c.getId(), c.getName(), c.getStatus(), c.getStartsOn(), c.getEndsOn(),
        c.getOpenTime(), c.getCloseTime(), c.getInitialCash(), c.getFeeBp(), listedCount);
  }
}
