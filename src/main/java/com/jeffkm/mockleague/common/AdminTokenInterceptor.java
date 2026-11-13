package com.jeffkm.mockleague.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * /api/admin/** 를 관리자 토큰으로 막는다(5주차② 배포).
 * 인터넷에 올리면 누구나 대회를 만들고 시작할 수 있으므로, 운영(prod)에서는 토큰이 반드시 있어야 뜬다.
 * 로컬처럼 토큰을 비워 두면 검사하지 않는다(Swagger 로 바로 눌러 보기 위함).
 */
public class AdminTokenInterceptor implements HandlerInterceptor {

  public static final String HEADER = "X-Admin-Token";

  private final byte[] expected;

  public AdminTokenInterceptor(String token) {
    this.expected = token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String given = request.getHeader(HEADER);
    // MessageDigest.isEqual 은 앞에서 몇 글자가 맞았는지에 따라 걸리는 시간이 달라지지 않는다(시간 차로 토큰을 알아내는 공격 방지)
    if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
      // 여기서 던진 예외도 GlobalExceptionHandler 가 받아 ApiResponse 형식의 401 로 바꾼다
      throw new BusinessException(ErrorCode.ADMIN_UNAUTHORIZED);
    }
    return true;
  }
}
