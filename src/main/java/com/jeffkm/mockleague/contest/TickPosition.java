package com.jeffkm.mockleague.contest;

import java.time.LocalDate;

/** 가격 경로 위의 한 지점: 몇 월 며칠의 몇 번째 틱인가. */
public record TickPosition(LocalDate day, int tick) {}
