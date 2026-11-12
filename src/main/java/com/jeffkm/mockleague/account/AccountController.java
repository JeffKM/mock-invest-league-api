package com.jeffkm.mockleague.account;

import com.jeffkm.mockleague.account.dto.AccountResponse;
import com.jeffkm.mockleague.account.dto.JoinResponse;
import com.jeffkm.mockleague.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 컨트롤러는 HTTP ↔ 서비스 변환만 한다. 규칙과 트랜잭션은 서비스에 있다. */
@RestController
@RequestMapping("/api/contests/{contestId}")
public class AccountController {

  /** 1주차 임시 인증 헤더. 3주차 이후 JWT 로 바꾸면 이 상수와 헤더 읽는 곳만 고친다. */
  public static final String USER_HEADER = "X-User-Id";

  private final AccountService accountService;

  public AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping("/participants")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<JoinResponse> join(@PathVariable Long contestId,
      @RequestHeader(USER_HEADER) Long userId) {
    return ApiResponse.ok(accountService.join(contestId, userId));
  }

  @GetMapping("/me")
  public ApiResponse<AccountResponse> me(@PathVariable Long contestId,
      @RequestHeader(USER_HEADER) Long userId) {
    return ApiResponse.ok(accountService.getMyAccount(contestId, userId));
  }
}
