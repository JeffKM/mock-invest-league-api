package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.account.CashLedger;
import com.jeffkm.mockleague.account.CashLedgerRepository;
import com.jeffkm.mockleague.account.Holding;
import com.jeffkm.mockleague.account.HoldingId;
import com.jeffkm.mockleague.account.HoldingRepository;
import com.jeffkm.mockleague.account.LedgerEntryType;
import com.jeffkm.mockleague.account.Participant;
import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.company.Company;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 지정가 주문의 돈·주식 이동(F8). 접수(묶기) · 체결 · 취소/만료(풀기) 세 곳에서 같은 계산을 써야 하므로 한 곳에 모았다.
 * 트랜잭션과 락은 부르는 쪽(OrderService, LimitOrderExecutor)이 책임진다.
 *
 * <p>체결 가격은 지정가다(docs/01 6절의 현재 안). 그래서 매수 때 묶는 돈 = 지정가 × 수량 + 그 수수료이고,
 * 체결 때 그 돈을 정확히 다 쓴다. 남거나 모자라는 돈이 없다.
 */
@Component
public class LimitOrderAccounting {

  private final HoldingRepository holdingRepository;
  private final CashLedgerRepository ledgerRepository;

  public LimitOrderAccounting(HoldingRepository holdingRepository, CashLedgerRepository ledgerRepository) {
    this.holdingRepository = holdingRepository;
    this.ledgerRepository = ledgerRepository;
  }

  /** 매수 주문이 묶는 현금 = 체결 금액 + 수수료. 접수·체결·풀기가 모두 이 값을 쓴다. */
  static long reservedCashFor(Order order, int feeBp) {
    long amount = Math.multiplyExact(order.getLimitPrice(), order.getQuantity());
    return Math.addExact(amount, FeePolicy.fee(amount, feeBp));
  }

  /** 접수: 매수면 현금을, 매도면 주식을 묶는다. 부족하면 업무 예외(I1, I2). 원장에는 남기지 않는다(총액 그대로). */
  public void reserve(Participant participant, Company company, Order order) {
    if (order.getSide() == OrderSide.BUY) {
      participant.reserve(reservedCashFor(order, participant.getContest().getFeeBp()));
    } else {
      holdingRepository.findById(new HoldingId(participant.getId(), company.getId()))
          .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_QUANTITY))
          .reserve(order.getQuantity());
    }
  }

  /** 취소·만료: 묶은 것을 그대로 돌려준다. 주문 상태 변경은 부르는 쪽이 한다. */
  public void release(Participant participant, Order order) {
    if (order.getSide() == OrderSide.BUY) {
      participant.release(reservedCashFor(order, participant.getContest().getFeeBp()));
    } else {
      holding(participant, order).release(order.getQuantity());
    }
  }

  /**
   * 체결: 지정가로 전량 체결한다. 묶은 것을 쓰고, 주문을 FILLED 로 바꾸고, 원장에 거래·수수료를 남긴다(I5).
   *
   * @param at 가격이 닿은 틱의 시각(배치가 돈 시각이 아니다)
   */
  public void fill(Participant participant, Order order, int tick, Instant at) {
    long price = order.getLimitPrice();
    long amount = Math.multiplyExact(price, order.getQuantity());
    long fee = FeePolicy.fee(amount, participant.getContest().getFeeBp());
    LedgerEntryType tradeType;
    if (order.getSide() == OrderSide.BUY) {
      participant.payFromReserved(Math.addExact(amount, fee));
      Holding holding = holdingRepository.findById(new HoldingId(participant.getId(), order.getCompany().getId()))
          .orElseGet(() -> holdingRepository.save(Holding.empty(participant, order.getCompany())));
      holding.increase(order.getQuantity(), amount);
      tradeType = LedgerEntryType.BUY;
    } else {
      holding(participant, order).sellReserved(order.getQuantity());
      long proceeds = amount - fee;
      if (proceeds > 0) {
        participant.deposit(proceeds);
      }
      tradeType = LedgerEntryType.SELL;
    }
    order.fill(price, fee, tick, at);
    ledgerRepository.save(CashLedger.record(participant, tradeType, amount, order));
    if (fee > 0) {
      ledgerRepository.save(CashLedger.record(participant, LedgerEntryType.FEE, fee, order));
    }
  }

  private Holding holding(Participant participant, Order order) {
    // 매도 주문을 접수할 때 묶었으므로 보유 행은 반드시 있다. 없으면 데이터가 깨진 것
    return holdingRepository.findById(new HoldingId(participant.getId(), order.getCompany().getId()))
        .orElseThrow(() -> new IllegalStateException("묶인 주식의 보유 행이 없습니다: order=" + order.getId()));
  }
}
