package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 비관적 락: 계좌 행을 FOR UPDATE 로 잠가 줄 세운다. */
@SpringBootTest(properties = {"league.order.lock-strategy=PESSIMISTIC", "league.order.max-attempts=5"})
@Import({PostgresTestConfig.class, TestClockConfig.class})
class PessimisticLockConcurrencyTest extends ConcurrentOrderScenarios {

  @Override
  boolean expectsAllToSucceed() {
    return true;
  }
}
