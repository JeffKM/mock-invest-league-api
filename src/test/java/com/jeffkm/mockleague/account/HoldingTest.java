package com.jeffkm.mockleague.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.company.Company;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.user.User;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class HoldingTest {

  private Holding newHolding() {
    Contest contest = Contest.withDefaults("테스트 대회", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), 42L);
    Participant p = Participant.join(contest, new User("tester"));
    // Company 는 시드로만 만들어져 공개 생성자가 없다. 단위 테스트에서는 가짜 객체(mock)로 대신한다
    return Holding.empty(p, mock(Company.class));
  }

  @Test
  void 두_번_사면_수량과_매입_총액이_더해지고_평균가가_나온다() {
    Holding h = newHolding();

    h.increase(10, 100_000); // 10주 × 10,000원
    h.increase(10, 120_000); // 10주 × 12,000원

    assertThat(h.getQuantity()).isEqualTo(20);
    assertThat(h.getTotalCost()).isEqualTo(220_000);
    assertThat(h.averagePrice()).isEqualTo(11_000);
  }

  @Test
  void 일부를_팔면_원가를_비율대로_덜어내고_평균가는_유지된다() {
    Holding h = newHolding();
    h.increase(20, 220_000);

    long removed = h.decrease(5);

    assertThat(removed).isEqualTo(55_000);
    assertThat(h.getQuantity()).isEqualTo(15);
    assertThat(h.getTotalCost()).isEqualTo(165_000);
    assertThat(h.averagePrice()).isEqualTo(11_000);
  }

  @Test
  void 전부_팔면_원가도_0이_된다_나누어_떨어지지_않아도() {
    Holding h = newHolding();
    h.increase(3, 100_000); // 평균 33,333.33...원

    h.decrease(1);
    h.decrease(2);

    assertThat(h.getQuantity()).isZero();
    assertThat(h.getTotalCost()).isZero(); // 버림 오차가 남지 않는다
  }

  @Test
  void I2_가진_것보다_많이_팔면_거절되고_보유는_그대로다() {
    Holding h = newHolding();
    h.increase(5, 50_000);

    assertThatThrownBy(() -> h.decrease(6))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.INSUFFICIENT_QUANTITY);
    assertThat(h.getQuantity()).isEqualTo(5);
    assertThat(h.getTotalCost()).isEqualTo(50_000);
  }

  @Test
  void 보유가_없으면_평균가는_0이다() {
    assertThat(newHolding().averagePrice()).isZero();
  }
}
