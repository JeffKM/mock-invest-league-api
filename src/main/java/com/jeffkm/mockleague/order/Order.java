package com.jeffkm.mockleague.order;

import com.jeffkm.mockleague.account.Participant;
import com.jeffkm.mockleague.company.Company;
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
import java.time.LocalDate;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 주문. 테이블 이름이 orders 인 이유: order 는 SQL 예약어(ORDER BY)다.
 * 상태는 PENDING 에서 한 번만 바뀐다(fill / cancel / expire).
 */
@Entity
@Table(name = "orders")
public class Order {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "participant_id")
  private Participant participant;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "company_id")
  private Company company;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 4)
  private OrderSide side;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 6)
  private OrderType orderType;

  @Column(nullable = false)
  private long quantity;

  /** 지정가 주문만 값이 있다(스키마 CHECK 와 같은 규칙). */
  private Long limitPrice;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private OrderStatus status;

  /** 체결 전에는 null. */
  private Long filledPrice;

  /** 체결 전에는 null. */
  private Long fee;

  @Column(nullable = false, length = 64)
  private String idempotencyKey;

  @Column(nullable = false)
  private LocalDate tradingDay;

  @Column(nullable = false)
  private int placedTick;

  private Integer filledTick;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  private Instant filledAt;

  protected Order() {}

  private Order(Participant participant, Company company, OrderSide side, OrderType orderType,
      long quantity, Long limitPrice, String idempotencyKey, LocalDate tradingDay, int placedTick) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("수량은 0보다 커야 합니다: " + quantity);
    }
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("Idempotency-Key 는 1~64자여야 합니다.");
    }
    this.participant = participant;
    this.company = company;
    this.side = side;
    this.orderType = orderType;
    this.quantity = quantity;
    this.limitPrice = limitPrice;
    this.status = OrderStatus.PENDING;
    this.idempotencyKey = idempotencyKey;
    this.tradingDay = tradingDay;
    this.placedTick = placedTick;
  }

  /** 시장가 주문을 접수한다(PENDING). 같은 트랜잭션 안에서 바로 fill 한다. */
  public static Order market(Participant participant, Company company, OrderSide side, long quantity,
      String idempotencyKey, LocalDate tradingDay, int placedTick) {
    return new Order(participant, company, side, OrderType.MARKET, quantity, null,
        idempotencyKey, tradingDay, placedTick);
  }

  /** 지정가 주문을 접수한다(PENDING). 3주차 정산 배치가 fill 또는 expire 한다. */
  public static Order limit(Participant participant, Company company, OrderSide side, long quantity,
      long limitPrice, String idempotencyKey, LocalDate tradingDay, int placedTick) {
    if (limitPrice <= 0) {
      throw new IllegalArgumentException("지정가는 0보다 커야 합니다: " + limitPrice);
    }
    return new Order(participant, company, side, OrderType.LIMIT, quantity, limitPrice,
        idempotencyKey, tradingDay, placedTick);
  }

  /** 체결. PENDING 이 아니면 프로그램 오류(이중 체결 시도). */
  public void fill(long price, long fee, int tick, Instant at) {
    requirePending("체결");
    if (price <= 0 || fee < 0) {
      throw new IllegalArgumentException("체결가는 0보다 크고 수수료는 0 이상이어야 합니다.");
    }
    this.status = OrderStatus.FILLED;
    this.filledPrice = price;
    this.fee = fee;
    this.filledTick = tick;
    this.filledAt = at;
  }

  /** 사용자 취소(지정가 대기 주문만 의미가 있다). */
  public void cancel() {
    requirePending("취소");
    this.status = OrderStatus.CANCELED;
  }

  /** 장 마감까지 체결되지 않은 지정가 주문 만료. */
  public void expire() {
    requirePending("만료");
    this.status = OrderStatus.EXPIRED;
  }

  /** 체결 금액 = 체결가 × 수량(수수료 제외). 체결 전이면 오류. */
  public long filledAmount() {
    if (status != OrderStatus.FILLED) {
      throw new IllegalStateException("체결되지 않은 주문입니다: " + status);
    }
    return Math.multiplyExact(filledPrice, quantity);
  }

  private void requirePending(String action) {
    if (status != OrderStatus.PENDING) {
      throw new IllegalStateException(action + "할 수 없는 상태입니다: " + status);
    }
  }

  public Long getId() {
    return id;
  }

  public Participant getParticipant() {
    return participant;
  }

  public Company getCompany() {
    return company;
  }

  public OrderSide getSide() {
    return side;
  }

  public OrderType getOrderType() {
    return orderType;
  }

  public long getQuantity() {
    return quantity;
  }

  public Long getLimitPrice() {
    return limitPrice;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public Long getFilledPrice() {
    return filledPrice;
  }

  public Long getFee() {
    return fee;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public LocalDate getTradingDay() {
    return tradingDay;
  }

  public int getPlacedTick() {
    return placedTick;
  }

  public Integer getFilledTick() {
    return filledTick;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getFilledAt() {
    return filledAt;
  }
}
