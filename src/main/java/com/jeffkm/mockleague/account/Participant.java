package com.jeffkm.mockleague.account;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 한 사용자가 한 대회에 참가한 계좌. 잔고의 주인은 사용자가 아니라 "대회 참가"다.
 */
@Entity
@Table(name = "participants")
public class Participant {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "contest_id")
  private Contest contest;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id")
  private User user;

  @Column(nullable = false)
  private long cash;

  @Column(nullable = false)
  private long reservedCash;

  /**
   * 2주차 낙관적 락 실험용 컬럼. 지금은 일부러 @Version 을 붙이지 않는다.
   * 붙이면 그 순간부터 낙관적 락이 켜져, "락이 없을 때 갱신 손실이 생기는 것"을 먼저 재현할 수 없다.
   */
  @Column(nullable = false)
  private long version;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant joinedAt;

  protected Participant() {}

  /** 대회에 참가한다. 초기 자금은 대회 규칙을 따른다(원장 INITIAL 기록은 서비스가 함께 남긴다). */
  public static Participant join(Contest contest, User user) {
    Participant p = new Participant();
    p.contest = contest;
    p.user = user;
    p.cash = contest.getInitialCash();
    p.reservedCash = 0;
    return p;
  }

  /** 현금을 뺀다. 부족하면 업무 예외(I1). */
  public void withdraw(long amount) {
    requirePositive(amount);
    if (cash < amount) {
      throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
    }
    cash -= amount;
  }

  /** 현금을 더한다. */
  public void deposit(long amount) {
    requirePositive(amount);
    cash = Math.addExact(cash, amount); // 넘치면 조용히 음수가 되는 대신 예외
  }

  private static void requirePositive(long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("금액은 0보다 커야 합니다: " + amount);
    }
  }

  public Long getId() {
    return id;
  }

  public Contest getContest() {
    return contest;
  }

  public User getUser() {
    return user;
  }

  public long getCash() {
    return cash;
  }

  public long getReservedCash() {
    return reservedCash;
  }

  public long getVersion() {
    return version;
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }
}
