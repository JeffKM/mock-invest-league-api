package com.jeffkm.mockleague.account.dto;

import java.time.LocalDate;

/** 출석 결과: 출석한 날짜(한국 시간), 받은 보너스, 보너스를 받은 뒤의 현금. */
public record AttendanceResponse(LocalDate attendedOn, long bonus, long cash) {}
