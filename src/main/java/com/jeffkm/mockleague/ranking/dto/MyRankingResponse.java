package com.jeffkm.mockleague.ranking.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 내 순위. 스냅샷이 없거나 스냅샷 뒤에 참가했으면 me 가 null 이다(다음 스냅샷부터 나온다). */
public record MyRankingResponse(
    LocalDate tradingDay,
    Integer tickNo,
    LocalDateTime asOf,
    int totalParticipants,
    RankingResponse.Entry me) {}
