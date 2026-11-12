package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestRepository;
import com.jeffkm.mockleague.contest.TickPosition;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 지정가 정산 배치(F8, I10). 장 중에는 1분마다 "지금 틱까지" 닿은 주문을 체결하고,
 * 마지막 틱이 지나면 끝내 닿지 않은 주문을 만료시킨다. 같은 함수 하나가 둘 다 한다.
 */
@Service
public class LimitOrderSettlementService {

  private static final Logger log = LoggerFactory.getLogger(LimitOrderSettlementService.class);

  private final ContestRepository contestRepository;
  private final OrderRepository orderRepository;
  private final LimitOrderExecutor executor;

  public LimitOrderSettlementService(ContestRepository contestRepository, OrderRepository orderRepository,
      LimitOrderExecutor executor) {
    this.contestRepository = contestRepository;
    this.orderRepository = orderRepository;
    this.executor = executor;
  }

  /**
   * now 시각에 볼 수 있는 가격까지로 이 대회의 대기 주문을 정산한다.
   *
   * @return 결과별 건수
   */
  public Map<LimitOrderExecutor.Outcome, Integer> settle(Long contestId, LocalDateTime now) {
    Map<LimitOrderExecutor.Outcome, Integer> counts = new EnumMap<>(LimitOrderExecutor.Outcome.class);
    Contest contest = contestRepository.findById(contestId).orElseThrow();
    Optional<TickPosition> upTo = contest.latestVisibleTick(now);
    if (upTo.isEmpty()) {
      return counts; // 아직 가격이 하나도 공개되지 않았다
    }
    List<Long> ids = orderRepository.findPendingLimitOrderIds(contestId, upTo.get().day());
    for (Long id : ids) {
      try {
        counts.merge(executor.settle(id, upTo.get()), 1, Integer::sum);
      } catch (RuntimeException e) {
        // 한 주문의 실패가 나머지를 막지 않게 한다. 대기 상태로 남으므로 다음 실행 때 다시 시도된다
        log.error("지정가 정산 실패: order={}", id, e);
        counts.merge(LimitOrderExecutor.Outcome.PENDING, 1, Integer::sum);
      }
    }
    if (!ids.isEmpty()) {
      log.info("지정가 정산: contest={}, 기준={}, 결과={}", contestId, upTo.get(), counts);
    }
    return counts;
  }
}
