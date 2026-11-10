package com.jeffkm.mockleague.common;

/** 업무 규칙 위반. 서비스 계층에서 던지고 GlobalExceptionHandler가 ApiResponse로 바꾼다. */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    this(errorCode, errorCode.defaultMessage());
  }

  public BusinessException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }
}
