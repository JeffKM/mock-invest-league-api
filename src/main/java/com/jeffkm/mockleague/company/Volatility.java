package com.jeffkm.mockleague.company;

/**
 * 가격 엔진의 변동성 등급. 값은 "하루 동안 가격이 움직이는 정도(일간 표준편차)"다.
 * 예: MID 2% → 84,000원짜리 종목은 하루에 대략 ±1,680원 안팎에서 움직이는 날이 많다.
 */
public enum Volatility {
  LOW(0.010),
  MID(0.020),
  HIGH(0.035);

  private final double dailySigma;

  Volatility(double dailySigma) {
    this.dailySigma = dailySigma;
  }

  public double dailySigma() {
    return dailySigma;
  }
}
