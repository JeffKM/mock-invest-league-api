package com.jeffkm.mockleague.contest;

import com.jeffkm.mockleague.common.ApiResponse;
import com.jeffkm.mockleague.contest.dto.ContestResponse;
import com.jeffkm.mockleague.contest.dto.CreateContestRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 API. 경로를 /api/admin 으로 분리해 두면 나중에 인증 규칙을 경로 하나로 걸 수 있다. */
@RestController
@RequestMapping("/api/admin/contests")
public class ContestAdminController {

  private final ContestService contestService;

  public ContestAdminController(ContestService contestService) {
    this.contestService = contestService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<ContestResponse> create(@Valid @RequestBody CreateContestRequest request) {
    return ApiResponse.ok(contestService.create(request));
  }

  @PostMapping("/{contestId}/start")
  public ApiResponse<ContestResponse> start(@PathVariable Long contestId) {
    return ApiResponse.ok(contestService.start(contestId));
  }
}
