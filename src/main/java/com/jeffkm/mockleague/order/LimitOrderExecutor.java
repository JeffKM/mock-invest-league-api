package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.account.Participant;
import com.jeffkm.mockleague.account.ParticipantRepository;
import com.jeffkm.mockleague.common.TimeConfig;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.TickPosition;
import com.jeffkm.mockleague.market.PriceTickRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지정가 주문 하나를 정산한다(주문 하나 = 트랜잭션 하나). 정산 서비스가 주문마다 이 빈을 부른다.
 * 별도 빈인 이유: 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 안 열린다.
 *
 * <p>I10(정확히 한 번 끝난다)을 지키는 방법:
 * <ol>
 *   <li>계좌를 비관적 락으로 잠근다. 취소 API 도 같은 락을 잡으므로 둘은 줄을 선다.</li>
 *   <li>잠근 "뒤에" 주문을 새로 읽어 아직 PENDING 인지 본다. 아니면 아무것도 하지 않는다.</li>
 *   <li>엔티티의 fill/cancel/expire 는 PENDING 이 아니면 예외를 던진다(마지막 방어선).</li>
 * </ol>
 * 그래서 배치를 두 번 돌려도, 서버 두 대가 동시에 돌려도, 취소와 겹쳐도 한 번만 처리된다.
 */
@Service
public class LimitOrderExecutor {

  public enum Outcome { FILLED, EXPIRED, PENDING, SKIPPED }

  private final OrderRepository orderRepository;
  private final ParticipantRepository participantRepository;
  private final PriceTickRepository priceTickRepository;
  private final LimitOrderAccounting accounting;

  public LimitOrderExecutor(OrderRepository orderRepository, ParticipantRepository participantRepository,
      PriceTickRepository priceTickRepository, LimitOrderAccounting accounting) {
    this.orderRepository = orderRepository;
    this.participantRepository = participantRepository;
    this.priceTickRepository = priceTickRepository;
    this.accounting = accounting;
  }

  /**
   * upTo 지점까지의 가격을 보고 체결·만료·대기 중 하나로 정한다.
   * 지난날 주문은 그날 마지막 틱까지 보고, 오늘 주문은 upTo 틱까지만 본다(미래 가격으로 체결하지 않는다).
   */
  @Transactional
  public Outcome settle(Long orderId, TickPosition upTo) {
    Optional<Long> participantId = orderRepository.findParticipantIdByOrderId(orderId);
    if (participantId.isEmpty()) {
      return Outcome.SKIPPED;
    }
    Participant participant = participantRepository.findByIdForUpdate(participantId.get()).orElseThrow();
    Order order = orderRepository.findById(orderId).orElseThrow(); // 락을 잡은 뒤에 읽는다
    if (order.getStatus() != OrderStatus.PENDING) {
      return Outcome.SKIPPED; // 이미 체결·취소·만료됨(다른 배치나 취소가 먼저 처리)
    }

    Contest contest = participant.getContest();
    int lastTick = contest.lastTick();
    int toTick = order.getTradingDay().isBefore(upTo.day()) ? lastTick : upTo.tick();
    Optional<Integer> touched = priceTickRepository.firstTouchTick(contest.getId(), order.getCompany().getId(),
        order.getTradingDay(), order.getPlacedTick(), toTick, order.getSide(), order.getLimitPrice());

    if (touched.isPresent()) {
      int tick = touched.get();
      Instant at = contest.tickStartAt(order.getTradingDay(), tick).atZone(TimeConfig.KST).toInstant();
      accounting.fill(participant, order, tick, at);
      return Outcome.FILLED;
    }
    if (toTick == lastTick) {
      accounting.release(participant, order);
      order.expire();
      return Outcome.EXPIRED;
    }
    return Outcome.PENDING;
  }
}
