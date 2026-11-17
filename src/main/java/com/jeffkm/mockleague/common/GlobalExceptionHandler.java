package com.jeffkm.mockleague.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
    if (e.errorCode() == ErrorCode.RATE_LIMITED || e.errorCode() == ErrorCode.SERVER_BUSY) {
      // 속도 제한(토큰은 1초 안에 다시 찬다)·덜어내기(6주차⑨): 몇 초 뒤에 다시 보내면 되는지 알려 준다.
      // 재전송하는 클라이언트가 바로 다시 몰려오지 않게 하는 신호다
      return ResponseEntity.status(e.errorCode().status())
          .header(HttpHeaders.RETRY_AFTER, "1")
          .body(ApiResponse.fail(e.errorCode(), e.getMessage()));
    }
    return respond(e.errorCode(), e.getMessage());
  }

  /**
   * 요청 형식 오류: 검증 실패(@Valid), 필수 헤더 없음, JSON 깨짐·없는 enum 값, 경로 변수 타입 불일치.
   * 모두 클라이언트 잘못이므로 500 이 아니라 400 이다.
   */
  @ExceptionHandler({
      MethodArgumentNotValidException.class,
      MissingRequestHeaderException.class,
      HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleInvalid(Exception e) {
    return respond(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage());
  }

  /**
   * DB 제약 위반(유니크·CHECK). 서비스 검증을 통과했는데 여기까지 왔다면
   * 거의 항상 동시 요청이 겹친 경우다(예: 같은 키 주문 두 개가 동시에 INSERT).
   * 주문은 OrderFacade 가 먼저 다시 시도하므로, 여기까지 오면 재시도를 다 써 버린 경우다.
   * 사용자에게는 409 로 알리고, 원인 추적을 위해 로그를 남긴다.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleConflict(DataIntegrityViolationException e) {
    log.warn("DB 제약 위반: {}", e.getMostSpecificCause().getMessage());
    return respond(ErrorCode.DATA_CONFLICT, ErrorCode.DATA_CONFLICT.defaultMessage());
  }

  /** 낙관적 락 재시도(OrderFacade)를 모두 써 버린 경우. 돈은 그대로이니 다시 시도하라고 알린다. */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException e) {
    return respond(ErrorCode.DATA_CONFLICT, ErrorCode.DATA_CONFLICT.defaultMessage());
  }

  /**
   * DB 커넥션을 제한 시간(운영 3초) 안에 못 얻었다 = 서버가 지금 감당할 수 있는 양을 넘었다(5주차② k6 에서 발견).
   * 500 + 스택 트레이스로 남기면 로그 쓰기 자체가 CPU 를 먹어 더 느려지는 악순환이 생긴다.
   * 503 + Retry-After 로 "잠시 뒤 다시"를 알리고, 로그는 한 줄만 남긴다.
   */
  @ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class})
  public ResponseEntity<ApiResponse<Void>> handleBusy(NestedRuntimeException e) {
    log.warn("DB 커넥션 부족으로 503: {}", e.getMostSpecificCause().getMessage());
    return ResponseEntity.status(ErrorCode.SERVER_BUSY.status())
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(ApiResponse.fail(ErrorCode.SERVER_BUSY, ErrorCode.SERVER_BUSY.defaultMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    // Spring MVC 가 던지는 표준 예외(없는 경로 404, 허용 안 된 메서드 405, 지원 안 하는 Content-Type 415 등)는
    // 이미 알맞은 상태 코드를 들고 있다. 이것까지 500 으로 바꾸면 클라이언트 잘못이 서버 장애처럼 보인다
    if (e instanceof ErrorResponse spring) {
      HttpStatusCode status = spring.getStatusCode();
      ErrorCode code = status.value() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.INVALID_REQUEST;
      return ResponseEntity.status(status).body(ApiResponse.fail(code, code.defaultMessage()));
    }
    // 예상하지 못한 오류는 로그로 남기고, 내부 메시지는 응답에 노출하지 않는다
    log.error("처리되지 않은 예외", e);
    return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
  }

  private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message) {
    return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, message));
  }
}
