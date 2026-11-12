package com.jeffkm.mockleague.contest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일일 배치를 정해진 시각에 부른다. 본체는 ContestLifecycleService 에 있고 여기는 "언제"만 정한다.
 * 장 시작 전(08:50)과 장 마감 후(15:40)에 일일 배치를, 장 중에는 1분마다 지정가 정산과 5분마다 순위 스냅샷을 돈다. 같은 일을 두 번 해도 안전하므로,
 * 한 번이 실패하거나 서버가 꺼져 있었어도 다음 번이 채운다.
 *
 * <p>league.scheduling.enabled=false 면 등록되지 않는다(테스트는 배치를 직접 부른다).
 * 서버를 여러 대 띄우면 모두가 돈다. 결과는 같지만 일을 중복하므로, 그때는 ShedLock 같은 분산 락을 붙인다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "league.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ContestScheduler {

  private final ContestLifecycleService lifecycleService;
  private final Clock clock; // 운영 Clock 빈은 한국 시간대(TimeConfig)라 날짜도 한국 기준이다

  public ContestScheduler(ContestLifecycleService lifecycleService, Clock clock) {
    this.lifecycleService = lifecycleService;
    this.clock = clock;
  }

  @Scheduled(cron = "0 50 8 * * *", zone = "Asia/Seoul")
  public void beforeOpen() {
    lifecycleService.runDaily(LocalDate.now(clock));
  }

  /**
   * 장 중(09:00~15:59) 1분마다 지정가 정산. 틱은 5분마다 바뀌지만 1분마다 보면 닿은 뒤 1분 안에 체결된다.
   * 15:30 이후의 실행이 마지막 틱까지 보고 남은 주문을 만료시킨다.
   */
  @Scheduled(cron = "0 * 9-15 * * *", zone = "Asia/Seoul")
  public void settleLimitOrders() {
    lifecycleService.settleRunning(LocalDateTime.now(clock));
  }

  /**
   * 장 중 5분(틱)마다 순위 스냅샷. 30초에 도는 이유: 매분 0초의 지정가 정산이 먼저 끝난 상태를 찍기 위함.
   * 15:35 실행이 그날 종가(마지막 틱) 기준 스냅샷이 된다. 이후 실행은 같은 틱이라 건너뛴다.
   */
  @Scheduled(cron = "30 */5 9-15 * * *", zone = "Asia/Seoul")
  public void snapshotRankings() {
    lifecycleService.snapshotRunning(LocalDateTime.now(clock));
  }

  @Scheduled(cron = "0 40 15 * * *", zone = "Asia/Seoul")
  public void afterClose() {
    lifecycleService.runDaily(LocalDate.now(clock));
  }
}
