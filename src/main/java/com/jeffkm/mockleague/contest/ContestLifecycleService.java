package com.jeffkm.mockleague.contest;

import com.jeffkm.mockleague.market.PriceGenerationService;
import com.jeffkm.mockleague.order.LimitOrderSettlementService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일일 배치의 본체(F2, F4). 날짜만 받으므로 테스트에서 "12월 2일 아침"처럼 아무 날이나 넣어 돌려 볼 수 있다.
 *
 * <p>한 번 돌 때 하는 일:
 * <ol>
 *   <li>시작일이 된 예정 대회를 시작한다(가격도 함께 만든다).</li>
 *   <li>지난날에 밀린 지정가 주문을 정산한다(체결 또는 만료).</li>
 *   <li>종료일이 지난 진행 대회를 끝낸다(남은 지정가 주문을 먼저 정산한다).</li>
 *   <li>진행 중인 대회는 "내일"까지 가격을 만들어 둔다. 오늘 밤 서버가 죽어도 내일 장은 열린다.</li>
 * </ol>
 * 몇 번을 돌려도 결과가 같다(이미 시작·종료한 대회는 건너뛰고, 이미 만든 가격은 다시 만들지 않는다).
 * 대회마다 트랜잭션을 따로 쓴다: 한 대회에서 실패해도 다른 대회는 처리된다.
 */
@Service
public class ContestLifecycleService {

  private static final Logger log = LoggerFactory.getLogger(ContestLifecycleService.class);

  private final ContestRepository contestRepository;
  private final ContestService contestService;
  private final PriceGenerationService priceGenerationService;
  private final LimitOrderSettlementService settlementService;
  private final TransactionTemplate tx;

  public ContestLifecycleService(ContestRepository contestRepository, ContestService contestService,
      PriceGenerationService priceGenerationService, LimitOrderSettlementService settlementService,
      TransactionTemplate tx) {
    this.settlementService = settlementService;
    this.contestRepository = contestRepository;
    this.contestService = contestService;
    this.priceGenerationService = priceGenerationService;
    this.tx = tx;
  }

  public void runDaily(LocalDate today) {
    for (Contest c : contestRepository.findByStatus(ContestStatus.SCHEDULED)) {
      if (!c.getStartsOn().isAfter(today)) {
        safely(c, "시작", () -> contestService.start(c.getId()));
      }
    }
    for (Contest c : contestRepository.findByStatus(ContestStatus.RUNNING)) {
      // 오늘 0시 기준으로 볼 수 있는 가격 = 어제(또는 마지막 날) 종가까지 → 지난날 주문은 모두 체결 또는 만료된다
      safely(c, "지정가 정산", () -> settlementService.settle(c.getId(), today.atStartOfDay()));
      if (c.getEndsOn().isBefore(today)) {
        safely(c, "종료", () -> tx.executeWithoutResult(s -> contestRepository.findById(c.getId())
            .filter(Contest::isRunning)   // 다른 서버가 먼저 끝냈으면 건너뛴다
            .ifPresent(Contest::finish)));
      } else {
        safely(c, "가격 생성", () -> priceGenerationService.ensureGeneratedThrough(c.getId(), today.plusDays(1)));
      }
    }
  }

  /** 장 중 1분마다: 진행 중인 대회의 지정가 주문을 "지금 틱"까지 정산한다. */
  public void settleRunning(LocalDateTime now) {
    for (Contest c : contestRepository.findByStatus(ContestStatus.RUNNING)) {
      safely(c, "지정가 정산", () -> settlementService.settle(c.getId(), now));
    }
  }

  private void safely(Contest c, String what, Runnable work) {
    try {
      work.run();
    } catch (RuntimeException e) {
      // 한 대회의 실패가 배치 전체를 멈추지 않게 기록만 하고 넘어간다. 다음 실행 때 다시 시도된다
      log.error("일일 배치 실패: contest={}, 작업={}", c.getId(), what, e);
    }
  }
}
