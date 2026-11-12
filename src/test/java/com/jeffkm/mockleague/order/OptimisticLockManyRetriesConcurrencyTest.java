package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 낙관적 락(재시도 최대 50회): 재시도를 넉넉히 주면 모두 체결되지만 그만큼 느리다. */
@SpringBootTest(properties = {"league.order.lock-strategy=OPTIMISTIC", "league.order.max-attempts=50"})
@Import({PostgresTestConfig.class, TestClockConfig.class})
class OptimisticLockManyRetriesConcurrencyTest extends ConcurrentOrderScenarios {

  @Override
  boolean expectsAllToSucceed() {
    return true;
  }
}
