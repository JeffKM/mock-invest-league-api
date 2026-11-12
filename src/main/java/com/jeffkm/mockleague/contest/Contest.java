package com.jeffkm.mockleague.contest;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
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

  /** 참가는 시작 전(SCHEDULED)과 진행 중(RUNNING)에만 받는다(F3). */
  public boolean isJoinable() {
    return status == ContestStatus.SCHEDULED || status == ContestStatus.RUNNING;
  }

  /**
   * 지금 주문을 받을 수 있으면 현재 틱 번호를, 아니면 업무 예외를 돌려준다(I9).
   * 장 시간은 [open, close) 반열린 구간이다. 15:30 정각은 이미 마감이다.
   * 현재 시각은 부르는 쪽이 Clock 으로 구해 넘긴다(엔티티는 시계를 모른다 → 테스트가 쉽다).
   */
  public int openTickAt(LocalDateTime now) {
    if (!isRunning()) {
      throw new BusinessException(ErrorCode.CONTEST_NOT_RUNNING);
    }
    LocalDate day = now.toLocalDate();
    LocalTime time = now.toLocalTime();
    boolean inPeriod = !day.isBefore(startsOn) && !day.isAfter(endsOn);
    boolean inHours = !time.isBefore(openTime) && time.isBefore(closeTime);
    if (!inPeriod || !inHours) {
      throw new BusinessException(ErrorCode.MARKET_CLOSED);
    }
    return (int) (Duration.between(openTime, time).getSeconds() / tickSeconds);
  }

  /** 하루 틱 수. 09:00~15:30, 300초 간격이면 78개(0~77). 나누어떨어지지 않으면 마지막 짧은 틱도 센다. */
  public int ticksPerDay() {
    long seconds = Duration.between(openTime, closeTime).getSeconds();
    return (int) ((seconds + tickSeconds - 1) / tickSeconds);
  }

  /** 그날 그 틱이 시작하는 시각. 지정가 체결 시각을 "배치가 돈 시각"이 아니라 "가격이 닿은 시각"으로 남길 때 쓴다. */
  public LocalDateTime tickStartAt(LocalDate day, int tick) {
    return day.atTime(openTime).plusSeconds((long) tick * tickSeconds);
  }

  /** 하루의 마지막 틱 번호. */
  public int lastTick() {
    return ticksPerDay() - 1;
  }

  /** 거래일인가. 1차 범위에서는 대회 기간의 모든 날이 거래일이다(주말 포함). */
  public boolean isTradingDay(LocalDate day) {
    return !day.isBefore(startsOn) && !day.isAfter(endsOn);
  }

  /**
   * "지금 보여 줄 가격"이 있는 가장 최근 지점(F5). 미래 틱은 절대 돌려주지 않는다(I11).
   * <ul>
   *   <li>장 중: 오늘의 현재 틱</li>
   *   <li>장 시작 전: 전 거래일의 마지막 틱(종가)</li>
   *   <li>장 마감 후, 대회 종료 후: 그날(또는 마지막 날)의 마지막 틱</li>
   *   <li>첫 거래일 장 시작 전, 대회 시작 전: 없음 → 부르는 쪽이 상장 기준가를 쓴다</li>
   * </ul>
   * 상태(RUNNING 여부)는 보지 않는다. 끝난 대회도 마지막 종가로 평가해야 하기 때문이다.
   */
  public Optional<TickPosition> latestVisibleTick(LocalDateTime now) {
    LocalDate day = now.toLocalDate();
    LocalTime time = now.toLocalTime();
    int lastTick = ticksPerDay() - 1;
    if (day.isAfter(endsOn)) {
      return Optional.of(new TickPosition(endsOn, lastTick));
    }
    if (day.isBefore(startsOn)) {
      return Optional.empty();
    }
    if (time.isBefore(openTime)) {
      LocalDate prev = day.minusDays(1);
      return prev.isBefore(startsOn) ? Optional.empty() : Optional.of(new TickPosition(prev, lastTick));
    }
    if (!time.isBefore(closeTime)) {
      return Optional.of(new TickPosition(day, lastTick));
    }
    return Optional.of(new TickPosition(day, (int) (Duration.between(openTime, time).getSeconds() / tickSeconds)));
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
