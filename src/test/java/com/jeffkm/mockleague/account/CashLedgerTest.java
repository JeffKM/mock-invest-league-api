package com.jeffkm.mockleague.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CashLedgerTest {

  @Test
  void 들어오는_돈은_양수로_기록된다() {
    assertThat(CashLedger.record(null, LedgerEntryType.INITIAL, 1_000, null).getAmount()).isEqualTo(1_000);
    assertThat(CashLedger.record(null, LedgerEntryType.SELL, 1_000, null).getAmount()).isEqualTo(1_000);
    assertThat(CashLedger.record(null, LedgerEntryType.BONUS, 1_000, null).getAmount()).isEqualTo(1_000);
  }

  @Test
  void 나가는_돈은_음수로_기록된다() {
    assertThat(CashLedger.record(null, LedgerEntryType.BUY, 1_000, null).getAmount()).isEqualTo(-1_000);
    assertThat(CashLedger.record(null, LedgerEntryType.FEE, 5, null).getAmount()).isEqualTo(-5);
  }

  @Test
  void 크기가_0이하면_기록하지_않는다() {
    assertThatThrownBy(() -> CashLedger.record(null, LedgerEntryType.BUY, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CashLedger.record(null, LedgerEntryType.BUY, -100, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
