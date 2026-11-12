package com.jeffkm.mockleague.order;

/**
 * 같은 계좌로 주문이 동시에 들어올 때 잔고·보유를 지키는 방법(2주차 비교 실험).
 * 설정 league.order.lock-strategy 로 고른다. 기본값은 PESSIMISTIC(ROADMAP 14단계에 고른 이유).
 */
public enum LockStrategy {
  /** 계좌 행을 SELECT ... FOR UPDATE 로 먼저 잠근다. 다른 요청은 앞 트랜잭션이 끝날 때까지 기다린다. */
  PESSIMISTIC,
  /** 잠그지 않는다. 저장할 때 version 이 바뀌었으면 실패하고, 바깥(OrderFacade)에서 다시 시도한다. */
  OPTIMISTIC,
  /** 읽고-계산하고-덮어쓰지 않고, "cash = cash - ? where cash >= ?" 처럼 DB 가 한 문장으로 계산한다. */
  CONDITIONAL
}
