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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시장가 주문(F7). 규칙은 엔티티가 지키고, 이 서비스는 "순서"와 "트랜잭션 경계"만 책임진다.
 *
 * <p>같은 계좌로 요청이 동시에 오면 잔고를 읽고 → 계산하고 → 덮어쓰는 사이에 갱신 손실이 생길 수 있다.
 * 막는 방법은 {@link LockStrategy} 세 가지 중 설정으로 고른다. 재시도는 이 클래스가 아니라
 * {@link OrderFacade} 가 한다(재시도는 트랜잭션 "밖"에서 새 트랜잭션으로 해야 하기 때문).
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
  private final LockStrategy lockStrategy;
  private final LimitOrderAccounting limitOrderAccounting;

  public OrderService(ParticipantRepository participantRepository, CompanyRepository companyRepository,
      HoldingRepository holdingRepository, OrderRepository orderRepository,
      CashLedgerRepository ledgerRepository, PriceProvider priceProvider, Clock clock,
      @Value("${league.order.lock-strategy:PESSIMISTIC}") LockStrategy lockStrategy,
      LimitOrderAccounting limitOrderAccounting) {
    this.participantRepository = participantRepository;
    this.companyRepository = companyRepository;
    this.holdingRepository = holdingRepository;
    this.orderRepository = orderRepository;
    this.ledgerRepository = ledgerRepository;
    this.priceProvider = priceProvider;
    this.clock = clock;
    this.lockStrategy = lockStrategy;
    this.limitOrderAccounting = limitOrderAccounting;
  }

  /**
   * 하나의 트랜잭션 안에서: 멱등 확인 → 장 확인 → 가격·수수료 계산 → 잔고·보유 변경 → 주문 저장 → 원장 기록.
   * 중간에 예외가 나면 전부 롤백된다. "돈은 빠졌는데 주식은 안 들어온" 상태가 남지 않는다.
   * 지정가 주문은 3) 에서 갈라져, 체결 대신 현금·주식을 묶고 PENDING 으로 저장한다.
   */
  @Transactional
  public OrderResponse placeOrder(Long contestId, Long userId, String idempotencyKey,
      PlaceOrderRequest request) {
    validateKey(idempotencyKey);
    validateType(request);
    // 0) 계좌 읽기. 비관적 락이면 여기서 계좌 행을 잠근다. 멱등 확인보다 먼저 잠가야
    //    같은 키 요청 두 개가 동시에 와도 두 번째가 첫 번째의 커밋을 기다렸다가 "이미 있음"을 보게 된다
    Participant participant = loadParticipant(contestId, userId);

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
    if (request.orderType() == OrderType.LIMIT) {
      // 상장 여부만 확인(현재가는 쓰지 않는다). 체결은 정산 배치가 가격 경로를 보고 한다
      priceProvider.priceAt(contestId, company.getId(), now.toLocalDate(), tick);
      Order order = Order.limit(participant, company, request.side(), request.quantity(), request.limitPrice(),
          idempotencyKey, now.toLocalDate(), tick);
      limitOrderAccounting.reserve(participant, company, order);
      orderRepository.save(order);
      return OrderResponse.from(order);
    }
    long price = priceProvider.priceAt(contestId, company.getId(), now.toLocalDate(), tick); // 2)에서 정한 그 틱의 가격
    long amount = Math.multiplyExact(price, request.quantity());
    long fee = FeePolicy.fee(amount, contest.getFeeBp());

    // 4) 잔고·보유 변경. 부족하면 예외가 나고, 그 전에 바꾼 것은 롤백된다(I1, I2)
    LedgerEntryType tradeType;
    if (request.side() == OrderSide.BUY) {
      buy(participant, company, request.quantity(), amount, fee);
      tradeType = LedgerEntryType.BUY;
    } else {
      sell(participant, company, request.quantity(), amount, fee);
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

    // participant·holding 의 변경은 save 없이도 커밋 때 더티 체킹으로 UPDATE 된다.
    // 낙관적 락이면 이 UPDATE 에 "where version = ?" 가 붙고, 그 사이 다른 요청이 먼저 바꿨으면 여기서 실패한다
    return OrderResponse.from(order);
  }

  /**
   * 대기 중인 지정가 주문 취소(F9). 묶은 현금·주식을 돌려준다.
   * 정산 배치와 겹칠 수 있으므로 락 방식 설정과 관계없이 계좌를 비관적 락으로 잠근다(배치도 같은 락을 잡는다).
   * 잠근 "뒤에" 주문을 읽어야 배치가 방금 체결한 것을 본다.
   */
  @Transactional
  public OrderResponse cancel(Long contestId, Long userId, Long orderId) {
    Participant participant = participantRepository.findForUpdate(contestId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPANT));
    Order order = orderRepository.findByIdAndParticipantId(orderId, participant.getId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "주문을 찾을 수 없습니다."));
    if (order.getStatus() != OrderStatus.PENDING) {
      throw new BusinessException(ErrorCode.ORDER_NOT_CANCELABLE);
    }
    limitOrderAccounting.release(participant, order);
    order.cancel();
    return OrderResponse.from(order);
  }

  /** 내 주문 최근 50건. 회사(티커)를 join fetch 로 함께 가져와 N+1 을 막는다. */
  @Transactional(readOnly = true)
  public List<OrderResponse> recentOrders(Long contestId, Long userId) {
    Participant participant = participantRepository.findByContestIdAndUserId(contestId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPANT));
    return orderRepository.findRecentWithCompany(participant.getId(), PageRequest.of(0, 50)).stream()
        .map(OrderResponse::from)
        .toList();
  }

  private Participant loadParticipant(Long contestId, Long userId) {
    Optional<Participant> found = (lockStrategy == LockStrategy.PESSIMISTIC)
        ? participantRepository.findForUpdate(contestId, userId)
        : participantRepository.findByContestIdAndUserId(contestId, userId);
    return found.orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPANT));
  }

  private void buy(Participant participant, Company company, long qty, long amount, long fee) {
    long total = Math.addExact(amount, fee); // 금액과 수수료를 한 번에: 둘 중 하나만 빠지는 일이 없다
    if (lockStrategy == LockStrategy.CONDITIONAL) {
      // DB 가 "지금 값"을 기준으로 계산한다. 메모리의 participant.cash 는 옛값으로 남지만 쓰지 않는다
      if (participantRepository.withdrawIfEnough(participant.getId(), total) == 0) {
        throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
      }
      holdingRepository.insertEmptyIfAbsent(participant.getId(), company.getId());
      holdingRepository.increaseAtomically(participant.getId(), company.getId(), qty, amount);
      return;
    }
    participant.withdraw(total);
    Holding holding = holdingRepository.findById(new HoldingId(participant.getId(), company.getId()))
        .orElseGet(() -> holdingRepository.save(Holding.empty(participant, company)));
    holding.increase(qty, amount);
  }

  private void sell(Participant participant, Company company, long qty, long amount, long fee) {
    long proceeds = amount - fee; // 판 돈에서 수수료를 뺀 실수령액
    if (lockStrategy == LockStrategy.CONDITIONAL) {
      if (holdingRepository.decreaseIfAvailable(participant.getId(), company.getId(), qty) == 0) {
        throw new BusinessException(ErrorCode.INSUFFICIENT_QUANTITY);
      }
      if (proceeds > 0) {
        participantRepository.depositAtomically(participant.getId(), proceeds);
      }
      return;
    }
    Holding holding = holdingRepository.findById(new HoldingId(participant.getId(), company.getId()))
        .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_QUANTITY));
    holding.decrease(qty);
    if (proceeds > 0) {
      participant.deposit(proceeds);
    }
  }

  /** 같은 키 재요청: 내용까지 같으면 처음 결과(지정가는 지금 상태), 다르면 클라이언트 실수이므로 422. */
  private OrderResponse replay(Order order, PlaceOrderRequest request) {
    boolean same = order.getOrderType() == request.orderType()
        && Objects.equals(order.getLimitPrice(), request.limitPrice())
        && order.getSide() == request.side()
        && order.getQuantity() == request.quantity()
        && order.getCompany().getTicker().equals(request.ticker());
    if (!same) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
    return OrderResponse.from(order);
  }

  /** 지정가에는 가격이 꼭 있어야 하고, 시장가에는 없어야 한다(스키마 CHECK 와 같은 규칙을 입구에서). */
  private static void validateType(PlaceOrderRequest request) {
    boolean hasLimit = request.limitPrice() != null;
    if (request.orderType() == OrderType.LIMIT && !hasLimit) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "지정가 주문에는 limitPrice 가 필요합니다.");
    }
    if (request.orderType() == OrderType.MARKET && hasLimit) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "시장가 주문에는 limitPrice 를 보내지 않습니다.");
    }
  }

  private static void validateKey(String key) {
    if (key == null || key.isBlank() || key.length() > 64) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key 는 1~64자여야 합니다.");
    }
  }
}
