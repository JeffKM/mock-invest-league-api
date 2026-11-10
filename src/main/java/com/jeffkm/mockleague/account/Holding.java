package com.jeffkm.mockleague.account;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.company.Company;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * 계좌가 가진 한 종목의 주식. totalCost 는 지금 가진 수량을 사는 데 쓴 총액(수수료 제외)이다.
 */
@Entity
@Table(name = "holdings")
public class Holding {

  @EmbeddedId
  private HoldingId id;

  @MapsId("participantId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "participant_id")
  private Participant participant;

  @MapsId("companyId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "company_id")
  private Company company;

  @Column(nullable = false)
  private long quantity;

  @Column(nullable = false)
  private long reservedQuantity;

  @Column(nullable = false)
  private long totalCost;

  /** 2주차 낙관적 락 실험용. Participant 와 같은 이유로 아직 @Version 을 붙이지 않는다. */
  @Column(nullable = false)
  private long version;

  protected Holding() {}

  /** 처음 사는 종목이면 빈 보유를 만든다. 참가자는 이미 저장돼 id 가 있어야 한다. */
  public static Holding empty(Participant participant, Company company) {
    Holding h = new Holding();
    h.id = new HoldingId(participant.getId(), company.getId());
    h.participant = participant;
    h.company = company;
    return h;
  }

  /** 매수 체결: 수량과 매입 총액을 늘린다. */
  public void increase(long qty, long cost) {
    if (qty <= 0 || cost <= 0) {
      throw new IllegalArgumentException("수량과 금액은 0보다 커야 합니다.");
    }
    quantity = Math.addExact(quantity, qty);
    totalCost = Math.addExact(totalCost, cost);
  }

  /**
   * 매도 체결: 수량을 줄이고, 판 수량만큼의 매입 원가를 비율대로 덜어 낸다.
   * 묶인 수량(지정가 매도 대기)은 팔 수 없다(I2).
   *
   * @return 덜어 낸 매입 원가(실현 손익 계산용)
   */
  public long decrease(long qty) {
    if (qty <= 0) {
      throw new IllegalArgumentException("수량은 0보다 커야 합니다: " + qty);
    }
    if (availableQuantity() < qty) {
      throw new BusinessException(ErrorCode.INSUFFICIENT_QUANTITY);
    }
    // 전부 팔면 원가도 전부. 일부면 비율대로(원 미만 버림), 남은 원가가 음수가 되지 않게 한다
    long removedCost = (qty == quantity)
        ? totalCost
        : Math.multiplyExact(totalCost, qty) / quantity;
    quantity -= qty;
    totalCost -= removedCost;
    return removedCost;
  }

  /** 지금 팔 수 있는 수량 = 보유 − 묶인 수량. */
  public long availableQuantity() {
    return quantity - reservedQuantity;
  }

  /** 평균 매입가(원 미만 버림). 보유가 없으면 0. */
  public long averagePrice() {
    return quantity == 0 ? 0 : totalCost / quantity;
  }

  public HoldingId getId() {
    return id;
  }

  public Participant getParticipant() {
    return participant;
  }

  public Company getCompany() {
    return company;
  }

  public long getQuantity() {
    return quantity;
  }

  public long getReservedQuantity() {
    return reservedQuantity;
  }

  public long getTotalCost() {
    return totalCost;
  }

  public long getVersion() {
    return version;
  }
}
