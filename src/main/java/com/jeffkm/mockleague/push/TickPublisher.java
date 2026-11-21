package com.jeffkm.mockleague.push;

/**
 * 틱이 바뀐 대회의 시세·순위를 푸시 서버(league-push)로 넘긴다(7주차⑤, ROADMAP 44단계).
 * 계약: 바로 돌아오고 예외를 던지지 않는다. 푸시는 부가 기능이라 스냅샷(본업)을 막으면 안 된다.
 */
public interface TickPublisher {

  void publish(long contestId, String json);
}
