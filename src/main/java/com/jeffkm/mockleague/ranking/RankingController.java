package com.jeffkm.mockleague.ranking;

import com.jeffkm.mockleague.account.AccountController;
import com.jeffkm.mockleague.common.ApiResponse;
import com.jeffkm.mockleague.ranking.dto.MyRankingResponse;
import com.jeffkm.mockleague.ranking.dto.RankingResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 순위 조회. 언제나 마지막 스냅샷 기준이다(F12). */
@RestController
@RequestMapping("/api/contests/{contestId}/rankings")
public class RankingController {

  private final RankingService rankingService;

  public RankingController(RankingService rankingService) {
    this.rankingService = rankingService;
  }

  @GetMapping
  public ApiResponse<RankingResponse> top(@PathVariable Long contestId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return ApiResponse.ok(rankingService.top(contestId, limit));
  }

  @GetMapping("/me")
  public ApiResponse<MyRankingResponse> mine(@PathVariable Long contestId,
      @RequestHeader(AccountController.USER_HEADER) Long userId) {
    return ApiResponse.ok(rankingService.mine(contestId, userId));
  }
}
