package com.jeffkm.mockleague.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API 요청 앞에서 덜어내기 자리를 잡고, 응답이 끝나면 돌려준다. 컨트롤러·트랜잭션보다 먼저 돌므로
 * 거절된 요청은 DB 커넥션을 잡지 않는다. 자리를 잡은 요청만 afterCompletion 에서 돌려준다
 * (preHandle 이 true 를 돌려준 요청에만 afterCompletion 이 불린다).
 */
public class LoadSheddingInterceptor implements HandlerInterceptor {

  private final LoadShedder shedder;

  public LoadSheddingInterceptor(LoadShedder shedder) {
    this.shedder = shedder;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (shedder.tryEnter()) {
      return true;
    }
    throw new BusinessException(ErrorCode.SERVER_BUSY); // GlobalExceptionHandler 가 503 + Retry-After 로 바꾼다
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
      Exception ex) {
    shedder.exit();
  }
}
