package com.jeffkm.mockleague.common;

import org.springframework.http.HttpStatus;

/** 업무 오류 코드. 불변식 번호(docs/01)를 주석으로 연결한다. */
public enum ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
  INSUFFICIENT_CASH(HttpStatus.CONFLICT, "현금이 부족합니다."), // I1
  INSUFFICIENT_QUANTITY(HttpStatus.CONFLICT, "보유 수량이 부족합니다."), // I2
  MARKET_CLOSED(HttpStatus.CONFLICT, "장이 열려 있지 않습니다."), // I9
  CONTEST_NOT_RUNNING(HttpStatus.CONFLICT, "진행 중인 대회가 아닙니다."), // I9
  CONTEST_NOT_JOINABLE(HttpStatus.CONFLICT, "참가할 수 없는 대회입니다."), // F3
  ALREADY_JOINED(HttpStatus.CONFLICT, "이미 참가한 대회입니다."),
  NOT_PARTICIPANT(HttpStatus.NOT_FOUND, "참가하지 않은 대회입니다."),
  NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 쓰이는 닉네임입니다."),
  IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "같은 Idempotency-Key 로 다른 주문을 보냈습니다."), // I6
  DATA_CONFLICT(HttpStatus.CONFLICT, "다른 요청과 충돌했습니다. 다시 시도해 주세요."),
  ALREADY_ATTENDED(HttpStatus.CONFLICT, "오늘 출석 보너스를 이미 받았습니다."), // I7
  ORDER_NOT_CANCELABLE(HttpStatus.CONFLICT, "취소할 수 없는 주문입니다."), // I10
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}
