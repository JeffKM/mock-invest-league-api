package com.jeffkm.mockleague.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 출석 보너스(F11, I7) 통합 테스트. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class AttendanceTest {

  private static final long INITIAL = Contest.DEFAULT_INITIAL_CASH;
  private static final long BONUS = Contest.DEFAULT_ATTENDANCE_BONUS; // 50,000

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired ParticipantRepository participantRepository;
  @Autowired CashLedgerRepository ledgerRepository;
  @Autowired AttendanceService attendanceService;
  @Autowired AttendanceRepository attendanceRepository;

  ApiFixture api;
  long userId;
  long contestId;

  @BeforeEach
  void setUp() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    api = new ApiFixture(mvc);
    userId = api.createUser();
    contestId = api.createContest("2026-12-31", 42L, true);
    api.join(contestId, userId);
  }

  Participant participant() {
    return participantRepository.findByContestIdAndUserId(contestId, userId).orElseThrow();
  }

  @Test
  void 출석하면_보너스가_들어오고_BONUS_원장이_남는다() throws Exception {
    api.attend(contestId, userId)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.attendedOn").value("2026-12-01"))
        .andExpect(jsonPath("$.data.bonus").value(BONUS))
        .andExpect(jsonPath("$.data.cash").value(INITIAL + BONUS));

    Participant p = participant();
    assertThat(p.getCash()).isEqualTo(INITIAL + BONUS);
    assertThat(ledgerRepository.sumAmountByParticipantId(p.getId())).isEqualTo(p.getCash() + p.getReservedCash()); // I5
  }

  @Test
  void I7_같은_날_두_번째_출석은_409이고_돈은_한_번만_들어온다() throws Exception {
    api.attend(contestId, userId).andExpect(status().isCreated());
    clock.set(LocalDateTime.of(2026, 12, 1, 15, 0)); // 같은 날 오후
    api.attend(contestId, userId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ALREADY_ATTENDED"));

    assertThat(participant().getCash()).isEqualTo(INITIAL + BONUS);
  }

  @Test
  void 다음_날에는_다시_받는다() throws Exception {
    api.attend(contestId, userId).andExpect(status().isCreated());
    clock.set(LocalDateTime.of(2026, 12, 2, 8, 0)); // 장 시작 전이어도 거래일이면 받는다
    api.attend(contestId, userId).andExpect(status().isCreated());

    assertThat(participant().getCash()).isEqualTo(INITIAL + 2 * BONUS);
    assertThat(attendanceRepository.countByParticipant(participant().getId())).isEqualTo(2);
  }

  /**
   * 날짜는 한국 시간 기준이다. 23:59 와 다음 날 00:01 은 UTC 로는 같은 날(14:59, 15:01)이지만 한국 날짜로는 다른 날이다.
   * 서버가 UTC 날짜를 썼다면 두 번째 출석이 409 가 됐을 것이다.
   */
  @Test
  void 한국_자정을_넘기면_UTC로는_같은_날이어도_다른_날이다() throws Exception {
    clock.set(LocalDateTime.of(2026, 12, 1, 23, 59));
    api.attend(contestId, userId).andExpect(status().isCreated());
    clock.set(LocalDateTime.of(2026, 12, 2, 0, 1));
    api.attend(contestId, userId)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.attendedOn").value("2026-12-02"));
  }

  @Test
  void 진행_중이_아닌_대회와_참가하지_않은_대회는_받을_수_없다() throws Exception {
    long scheduled = api.createContest("2026-12-31", 1L, false);
    api.join(scheduled, userId);
    api.attend(scheduled, userId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("CONTEST_NOT_RUNNING"));

    long stranger = api.createUser();
    api.attend(contestId, stranger)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_PARTICIPANT"));
  }

  @Test
  void I7_동시에_10번_눌러도_한_번만_받는다() throws Exception {
    int threads = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<String>> results = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        Callable<String> task = () -> {
          start.await();
          try {
            attendanceService.attend(contestId, userId);
            return "OK";
          } catch (com.jeffkm.mockleague.common.BusinessException e) {
            return e.errorCode().name();
          }
        };
        results.add(pool.submit(task));
      }
      start.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> f : results) {
        outcomes.add(f.get());
      }
      assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
      assertThat(outcomes).filteredOn("ALREADY_ATTENDED"::equals).hasSize(threads - 1);
    } finally {
      pool.shutdownNow();
    }

    Participant p = participant();
    assertThat(p.getCash()).isEqualTo(INITIAL + BONUS);
    assertThat(ledgerRepository.sumAmountByParticipantId(p.getId())).isEqualTo(p.getCash()); // I5
  }
}
