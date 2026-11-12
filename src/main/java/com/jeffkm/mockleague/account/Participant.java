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
import jakarta.persistence.Version;
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
   * 낙관적 락용 버전. Hibernate 가 UPDATE 때마다 1씩 올리고 "where version = 읽었을 때 값"을 붙인다.
   * 그 사이 다른 트랜잭션이 먼저 고쳤으면 바뀐 행이 0개라 예외가 난다(덮어쓰기 대신 실패).
   * 비관적 락·조건부 UPDATE 방식에서도 켜져 있지만, 그때는 충돌할 일이 없어 숫자만 올라간다.
   */
  @Version
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

  /**
   * 지정가 매수 접수: 현금을 묶는다(cash → reservedCash). 총액이 그대로라 원장에는 남기지 않는다(I5).
   * 부족하면 업무 예외(I1).
   */
  public void reserve(long amount) {
    requirePositive(amount);
    if (cash < amount) {
      throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
    }
    cash -= amount;
    reservedCash = Math.addExact(reservedCash, amount);
  }

  /** 지정가 매수 취소·만료: 묶은 현금을 풀어 돌려준다(reservedCash → cash). */
  public void release(long amount) {
    requirePositive(amount);
    requireReserved(amount);
    reservedCash -= amount;
    cash = Math.addExact(cash, amount);
  }

  /** 지정가 매수 체결: 묶어 둔 현금으로 낸다(reservedCash 에서 빠져나감 → 원장에 BUY·FEE 로 남는다). */
  public void payFromReserved(long amount) {
    requirePositive(amount);
    requireReserved(amount);
    reservedCash -= amount;
  }

  private void requireReserved(long amount) {
    // 묶은 것보다 많이 풀거나 쓰는 것은 사용자 잘못이 아니라 프로그램 오류다(계산 불일치)
    if (reservedCash < amount) {
      throw new IllegalStateException("묶인 현금보다 많이 풀 수 없습니다: " + reservedCash + " < " + amount);
    }
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
