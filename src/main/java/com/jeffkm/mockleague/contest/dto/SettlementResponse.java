package com.jeffkm.mockleague.contest.dto;

/**
 * 관리자가 지정가 정산을 바로 돌린 결과(6주차⑧ P4). 결과별 주문 수와 걸린 시간.
 * pending 은 아직 닿지 않았거나 처리 중 오류가 나 다음 실행으로 넘어간 주문, skipped 는 이미 끝난(체결·취소·만료) 주문이다.
 */
public record SettlementResponse(int filled, int expired, int pending, int skipped, long elapsedMs) {}
