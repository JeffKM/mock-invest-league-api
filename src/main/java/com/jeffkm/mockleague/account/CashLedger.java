package com.jeffkm.mockleague.account;

import com.jeffkm.mockleague.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 현금 이동 한 건. 추가만 하고 고치지 않는다(setter 없음).
 * cash + reserved_cash = Σ amount (참가 때의 INITIAL 포함) 가 항상 성립해야 한다(I5).
 */
@Entity
@Table(name = "cash_ledger")
public class CashLedger {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "participant_id")
  private Participant participant;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private LedgerEntryType entryType;

  /** 들어오면 +, 나가면 − (부호는 entryType 이 정한다). */
  @Column(nullable = false)
  private long amount;

  /** 주문과 관계없는 기록(INITIAL, BONUS)은 null. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "order_id")
  private Order order;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  protected CashLedger() {}

  /**
   * 원장 한 줄을 만든다. size 는 항상 양수로 받고, 부호는 종류에 따라 붙인다.
   * 부르는 쪽이 부호를 직접 정하면 실수로 BUY 를 + 로 남길 수 있기 때문이다.
   */
  public static CashLedger record(Participant participant, LedgerEntryType type, long size, Order order) {
    if (size <= 0) {
      throw new IllegalArgumentException("원장 금액 크기는 0보다 커야 합니다: " + size);
    }
    CashLedger entry = new CashLedger();
    entry.participant = participant;
    entry.entryType = type;
    entry.amount = type.isInflow() ? size : -size;
    entry.order = order;
    return entry;
  }

  public Long getId() {
    return id;
  }

  public Participant getParticipant() {
    return participant;
  }

  public LedgerEntryType getEntryType() {
    return entryType;
  }

  public long getAmount() {
    return amount;
  }

  public Order getOrder() {
    return order;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
