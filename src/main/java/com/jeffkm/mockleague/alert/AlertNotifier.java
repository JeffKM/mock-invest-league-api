package com.jeffkm.mockleague.alert;

/**
 * 운영자에게 알리는 창구(7주차②). 부르는 쪽(배치·스케줄러)은 알림이 어디로 가는지, 얼마나 걸리는지 모른다.
 *
 * <p>약속: send 는 바로 돌아온다. 전송이 느리거나 실패해도 부른 쪽을 막거나 예외를 던지지 않는다.
 */
public interface AlertNotifier {

  void send(String text);
}
