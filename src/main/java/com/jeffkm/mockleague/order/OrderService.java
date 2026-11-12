package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.account.CashLedger;
import com.jeffkm.mockleague.account.CashLedgerRepository;
import com.jeffkm.mockleague.account.Holding;
import com.jeffkm.mockleague.account.HoldingId;
import com.jeffkm.mockleague.account.HoldingRepository;
import com.jeffkm.mockleague.account.LedgerEntryType;
import com.jeffkm.mockleague.account.Participant;
import com.jeffkm.mockleague.account.ParticipantRepository;
import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.company.Company;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.market.PriceProvider;
import com.jeffkm.mockleague.order.dto.OrderResponse;
import com.jeffkm.mockleague.order.dto.PlaceOrderRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시장가 주문(F7). 규칙은 엔티티가 지키고, 이 서비스는 "순서"와 "트랜잭션 경계"만 책임진다.
 *
 * <p>주의(일부러 남긴 문제): 잔고를 읽고 → 계산하고 → 덮어쓰는 동안 락이 없다.
 * 같은 계좌로 요청이 동시에 오면 갱신 손실이 생길 수 있다. 2주차에 재현한 뒤 락을 비교해 고친다.
 */
@Service
public class OrderService {

  private final ParticipantRepository participantRepository;
  private final CompanyRepository companyRepository;
  private final HoldingRepository holdingRepository;
  private final OrderRepository orderRepository;
  private final CashLedgerRepository ledgerRepository;
  private final PriceProvider priceProvider;
  private final Clock clock;

  public OrderService(ParticipantRepository participantRepository, CompanyRepository companyRepository,
      HoldingRepository holdingRepository, OrderRepository orderRepository,
      CashLedgerRepository ledgerRepository, PriceProvider priceProvider, Clock clock) {
    this.participantRepository = participantRepository;
    this.companyRepository = companyRepository;
    this.holdingRepository = holdingRepository;
    this.orderRepository = orderRepository;
    this.ledgerRepository = ledgerRepository;
    this.priceProvider = priceProvider;
    this.clock = clock;
  }

  /**
   * 하나의 트랜잭션 안에서: 멱등 확인 → 장 확인 → 가격·수수료 계산 → 잔고·보유 변경 → 주문 저장 → 원장 기록.
   * 중간에 예외가 나면 전부 롤백된다. "돈은 빠졌는데 주식은 안 들어온" 상태가 남지 않는다.
   */
  @Transactional
  public OrderResponse placeMarketOrder(Long contestId, Long userId, String idempotencyKey,
      PlaceOrderRequest request) {
    validateKey(idempotencyKey);
    Participant participant = participantRepository.findByContestIdAndUserId(contestId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPANT));

    // 1) 멱등성(I6): 같은 키로 이미 처리한 주문이 있으면 다시 실행하지 않고 처음 결과를 돌려준다.
    //    장 시간 확인보다 먼저 해야 한다. 장 마감 직후 재전송된 요청에도 "체결됐다"는 같은 답을 줘야 하기 때문.
    Optional<Order> existing =
        orderRepository.findByParticipantIdAndIdempotencyKey(participant.getId(), idempotencyKey);
    if (existing.isPresent()) {
      return replay(existing.get(), request);
    }

    // 2) 장 확인(I9). 현재 시각은 주입받은 Clock 으로 한 번만 구해 끝까지 같은 값을 쓴다
    //    (중간에 다시 구하면 15:29:59 와 15:30:00 사이에 걸쳐 틱과 거래일이 어긋날 수 있다)
    Instant at = clock.instant();
    LocalDateTime now = LocalDateTime.ofInstant(at, clock.getZone());
    Contest contest = participant.getContest();
    int tick = contest.openTickAt(now);

    // 3) 가격과 금액·수수료(I3, I4)
    Company company = companyRepository.findByTicker(request.ticker())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "종목을 찾을 수 없습니다: " + request.ticker()));
    long price = priceProvider.currentPrice(contestId, company.getId());
    long amount = Math.multiplyExact(price, request.quantity());
    long fee = FeePolicy.fee(amount, contest.getFeeBp());

    // 4) 잔고·보유 변경. 부족하면 엔티티가 예외를 던지고, 그 전에 바꾼 것은 롤백된다(I1, I2)
    LedgerEntryType tradeType;
    if (request.side() == OrderSide.BUY) {
      participant.withdraw(Math.addExact(amount, fee)); // 금액과 수수료를 한 번에: 둘 중 하나만 빠지는 일이 없다
      Holding holding = holdingRepository.findById(new HoldingId(participant.getId(), company.getId()))
          .orElseGet(() -> holdingRepository.save(Holding.empty(participant, company)));
      holding.increase(request.quantity(), amount);
      tradeType = LedgerEntryType.BUY;
    } else {
      Holding holding = holdingRepository.findById(new HoldingId(participant.getId(), company.getId()))
          .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_QUANTITY));
      holding.decrease(request.quantity());
      long proceeds = amount - fee; // 판 돈에서 수수료를 뺀 실수령액
      if (proceeds > 0) {
        participant.deposit(proceeds);
      }
      tradeType = LedgerEntryType.SELL;
    }

    // 5) 주문 저장 후 체결 처리. 원장이 order_id 를 참조하므로 주문이 먼저 저장돼야 한다
    Order order = Order.market(participant, company, request.side(), request.quantity(),
        idempotencyKey, now.toLocalDate(), tick);
    order.fill(price, fee, tick, at);
    orderRepository.save(order);

    // 6) 원장(I5): 거래 금액과 수수료를 따로 남긴다. 합치면 "수수료로 얼마 냈나"를 나중에 알 수 없다
    ledgerRepository.save(CashLedger.record(participant, tradeType, amount, order));
    if (fee > 0) {
      ledgerRepository.save(CashLedger.record(participant, LedgerEntryType.FEE, fee, order));
    }

    // participant·holding 의 변경은 save 없이도 커밋 때 더티 체킹으로 UPDATE 된다
    return OrderResponse.from(order);
  }

  /** 같은 키 재요청: 내용까지 같으면 처음 결과, 다르면 클라이언트 실수이므로 422. */
  private OrderResponse replay(Order order, PlaceOrderRequest request) {
    boolean same = order.getOrderType() == OrderType.MARKET
        && order.getSide() == request.side()
        && order.getQuantity() == request.quantity()
        && order.getCompany().getTicker().equals(request.ticker());
    if (!same) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
    return OrderResponse.from(order);
  }

  private static void validateKey(String key) {
    if (key == null || key.isBlank() || key.length() > 64) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key 는 1~64자여야 합니다.");
    }
  }
}
