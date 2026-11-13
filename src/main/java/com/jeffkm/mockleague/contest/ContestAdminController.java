package com.jeffkm.mockleague.contest;

import com.jeffkm.mockleague.common.ApiResponse;
import com.jeffkm.mockleague.contest.dto.ContestResponse;
import com.jeffkm.mockleague.contest.dto.CreateContestRequest;
import com.jeffkm.mockleague.contest.dto.SnapshotResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
  private final ContestLifecycleService lifecycleService;
  private final InvariantAuditService auditService;
  private final Clock clock;

  public ContestAdminController(ContestService contestService,
      ContestLifecycleService lifecycleService, InvariantAuditService auditService, Clock clock) {
    this.contestService = contestService;
    this.lifecycleService = lifecycleService;
    this.auditService = auditService;
    this.clock = clock;
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

  /** 지금 틱 기준 순위 스냅샷을 바로 찍는다. 스케줄러(5분마다)를 기다리지 않고 순위표를 확인할 때 쓴다. */
  @PostMapping("/{contestId}/ranking-snapshots")
  public ApiResponse<SnapshotResponse> snapshot(@PathVariable Long contestId) {
    return ApiResponse.ok(new SnapshotResponse(lifecycleService.snapshotNow(contestId, LocalDateTime.now(clock))));
  }

  /**
   * 불변식 검사(6주차③). 부하 테스트가 끝나면 k6 가 불러 "돈과 주식이 장부와 맞는가"를 확인한다.
   * 어긋난 것이 있어도 200 이고, 본문의 ok 와 각 검사의 violations 로 알린다(검사 자체는 성공했으므로).
   */
  @GetMapping("/{contestId}/invariants")
  public ApiResponse<InvariantAuditService.Report> invariants(@PathVariable Long contestId) {
    return ApiResponse.ok(auditService.audit(contestId));
  }
}
