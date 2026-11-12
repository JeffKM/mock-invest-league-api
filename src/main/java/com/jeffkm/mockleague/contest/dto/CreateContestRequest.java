package com.jeffkm.mockleague.contest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** seed 를 비우면 서버가 정한다. 같은 seed = 같은 가격 경로(3주차). */
public record CreateContestRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull LocalDate startsOn,
    @NotNull LocalDate endsOn,
    Long seed) {}
