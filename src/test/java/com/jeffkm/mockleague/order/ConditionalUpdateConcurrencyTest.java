package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 조건부 UPDATE: DB 가 한 문장으로 계산·검사한다. */
@SpringBootTest(properties = {"league.order.lock-strategy=CONDITIONAL", "league.order.max-attempts=5"})
@Import({PostgresTestConfig.class, TestClockConfig.class})
class ConditionalUpdateConcurrencyTest extends ConcurrentOrderScenarios {

  @Override
  boolean expectsAllToSucceed() {
    return true;
  }
}
