package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 낙관적 락(재시도 최대 5회): 같은 계좌에 몰리면 재시도를 다 써 버린 요청이 실패할 수 있다. 돈은 언제나 맞아야 한다. */
@SpringBootTest(properties = {"league.order.lock-strategy=OPTIMISTIC", "league.order.max-attempts=5"})
@Import({PostgresTestConfig.class, TestClockConfig.class})
class OptimisticLockConcurrencyTest extends ConcurrentOrderScenarios {

  @Override
  boolean expectsAllToSucceed() {
    return false;
  }
}
