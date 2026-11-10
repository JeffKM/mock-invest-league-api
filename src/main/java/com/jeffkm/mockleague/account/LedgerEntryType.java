package com.jeffkm.mockleague.account;

/**
 * 원장 기록 종류와 금액 부호 규칙.
 * 들어오는 돈(INITIAL, SELL, BONUS)은 +, 나가는 돈(BUY, FEE)은 −.
 */
public enum LedgerEntryType {
  INITIAL(true),
  BUY(false),
  SELL(true),
  FEE(false),
  BONUS(true);

  private final boolean inflow;

  LedgerEntryType(boolean inflow) {
    this.inflow = inflow;
  }

  public boolean isInflow() {
    return inflow;
  }
}
