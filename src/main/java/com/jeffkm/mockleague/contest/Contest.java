package com.jeffkm.mockleague.contest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.hibernate.annotations.CreationTimestamp;

/** 대회 하나의 규칙(기간·장 시간·초기 자금·수수료·제한폭·보너스·가격 시드). */
@Entity
@Table(name = "contests")
public class Contest {

  // 기본값(docs/01). 스키마의 default 와 같은 값이어야 한다
  public static final LocalTime DEFAULT_OPEN = LocalTime.of(9, 0);
  public static final LocalTime DEFAULT_CLOSE = LocalTime.of(15, 30);
  public static final int DEFAULT_TICK_SECONDS = 300;
  public static final long DEFAULT_INITIAL_CASH = 10_000_000L;
  public static final int DEFAULT_FEE_BP = 50; // 0.5%
  public static final int DEFAULT_PRICE_LIMIT_BP = 3_000; // ±30%
  public static final long DEFAULT_ATTENDANCE_BONUS = 50_000L;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ContestStatus status;

  @Column(nullable = false)
  private LocalDate startsOn;

  @Column(nullable = false)
  private LocalDate endsOn;

  @Column(nullable = false)
  private LocalTime openTime;

  @Column(nullable = false)
  private LocalTime closeTime;

  @Column(nullable = false)
  private int tickSeconds;

  @Column(nullable = false)
  private long initialCash;

  @Column(nullable = false)
  private int feeBp;

  @Column(nullable = false)
  private int priceLimitBp;

  @Column(nullable = false)
  private long attendanceBonus;

  @Column(nullable = false)
  private long seed;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  protected Contest() {}

  private Contest(String name, LocalDate startsOn, LocalDate endsOn, long seed) {
    if (startsOn.isAfter(endsOn)) {
      throw new IllegalArgumentException("시작일이 종료일보다 늦을 수 없습니다.");
    }
    this.name = name;
    this.status = ContestStatus.SCHEDULED;
    this.startsOn = startsOn;
    this.endsOn = endsOn;
    this.openTime = DEFAULT_OPEN;
    this.closeTime = DEFAULT_CLOSE;
    this.tickSeconds = DEFAULT_TICK_SECONDS;
    this.initialCash = DEFAULT_INITIAL_CASH;
    this.feeBp = DEFAULT_FEE_BP;
    this.priceLimitBp = DEFAULT_PRICE_LIMIT_BP;
    this.attendanceBonus = DEFAULT_ATTENDANCE_BONUS;
    this.seed = seed;
  }

  /** 기본 규칙으로 대회를 만든다. 상태는 SCHEDULED 로 시작한다. */
  public static Contest withDefaults(String name, LocalDate startsOn, LocalDate endsOn, long seed) {
    return new Contest(name, startsOn, endsOn, seed);
  }

  /** SCHEDULED → RUNNING. 다른 상태에서 부르면 프로그램 오류다. */
  public void start() {
    if (status != ContestStatus.SCHEDULED) {
      throw new IllegalStateException("예정 상태의 대회만 시작할 수 있습니다: " + status);
    }
    status = ContestStatus.RUNNING;
  }

  /** RUNNING → FINISHED. */
  public void finish() {
    if (status != ContestStatus.RUNNING) {
      throw new IllegalStateException("진행 중인 대회만 끝낼 수 있습니다: " + status);
    }
    status = ContestStatus.FINISHED;
  }

  public boolean isRunning() {
    return status == ContestStatus.RUNNING;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public ContestStatus getStatus() {
    return status;
  }

  public LocalDate getStartsOn() {
    return startsOn;
  }

  public LocalDate getEndsOn() {
    return endsOn;
  }

  public LocalTime getOpenTime() {
    return openTime;
  }

  public LocalTime getCloseTime() {
    return closeTime;
  }

  public int getTickSeconds() {
    return tickSeconds;
  }

  public long getInitialCash() {
    return initialCash;
  }

  public int getFeeBp() {
    return feeBp;
  }

  public int getPriceLimitBp() {
    return priceLimitBp;
  }

  public long getAttendanceBonus() {
    return attendanceBonus;
  }

  public long getSeed() {
    return seed;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
