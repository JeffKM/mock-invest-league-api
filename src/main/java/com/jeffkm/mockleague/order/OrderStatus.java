package com.jeffkm.mockleague.order;

/**
 * 주문 상태. PENDING 에서만 다른 상태로 갈 수 있고, 나머지는 끝난 상태다(I10).
 * REJECTED 는 접수 기록은 남기되 실행하지 않은 주문(예: 검증 실패를 기록하고 싶을 때)에 쓴다.
 */
public enum OrderStatus {
  PENDING,
  FILLED,
  CANCELED,
  EXPIRED,
  REJECTED;

  public boolean isFinal() {
    return this != PENDING;
  }
}
