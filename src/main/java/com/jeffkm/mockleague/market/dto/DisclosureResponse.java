package com.jeffkm.mockleague.market.dto;

import com.jeffkm.mockleague.company.Sector;
import com.jeffkm.mockleague.market.DisclosureScope;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 공시 한 건. 회사 공시면 ticker·companyName 이, 섹터 공시면 sector 가 채워진다.
 * 충격 크기는 내보내지 않는다: 참가자는 제목만 보고 판단해야 한다.
 */
public record DisclosureResponse(
    Long id,
    LocalDate tradingDay,
    int tickNo,
    LocalDateTime publishedAt,
    DisclosureScope scope,
    String ticker,
    String companyName,
    Sector sector,
    String title) {}
