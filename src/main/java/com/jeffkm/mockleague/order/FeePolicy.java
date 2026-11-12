package com.jeffkm.mockleague.order;

/**
 * 수수료 계산은 이 한 곳에서만 한다(I4).
 * 여러 곳에서 각자 계산하면 반올림·버림 규칙이 어긋나 원장 합계가 틀어진다.
 */
public final class FeePolicy {

  /** 1bp = 0.01% 이므로 100% = 10,000bp. */
  public static final int BP_DENOMINATOR = 10_000;

  private FeePolicy() {}

  /**
   * 수수료 = 체결 금액 × 수수료율, 원 미만 버림.
   * 금액·비율이 모두 0 이상이므로 정수 나눗셈(버림)이 곧 내림이다.
   *
   * @throws ArithmeticException 곱셈이 long 범위를 넘을 때(조용히 음수가 되는 대신)
   */
  public static long fee(long amount, int feeBp) {
    if (amount < 0 || feeBp < 0 || feeBp > BP_DENOMINATOR) {
      throw new IllegalArgumentException("금액은 0 이상, 수수료율은 0~10000bp 여야 합니다.");
    }
    return Math.multiplyExact(amount, (long) feeBp) / BP_DENOMINATOR;
  }
}
