package com.jeffkm.mockleague.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
    ErrorCode code = e.errorCode();
    return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, e.getMessage()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class})
  public ResponseEntity<ApiResponse<Void>> handleInvalid(Exception e) {
    ErrorCode code = ErrorCode.INVALID_REQUEST;
    return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, code.defaultMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    // 예상하지 못한 오류는 로그로 남기고, 내부 메시지는 응답에 노출하지 않는다
    log.error("처리되지 않은 예외", e);
    ErrorCode code = ErrorCode.INTERNAL_ERROR;
    return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, code.defaultMessage()));
  }
}
