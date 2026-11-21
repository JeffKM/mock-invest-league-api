package com.jeffkm.mockleague.contest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.alert.AlertNotifier;
import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 7주차② 장 마감 뒤 불변식 검사: 어긋난 대회만 알림을 보낸다. 알림은 받은 글을 모아 두는 가짜로 바꾼다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class, InvariantAuditJobTest.RecordingConfig.class})
class InvariantAuditJobTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class RecordingConfig {
    @Bean
    @Primary
    RecordingNotifier recordingNotifier() {
      return new RecordingNotifier();
    }
  }

  static class RecordingNotifier implements AlertNotifier {
    final List<String> messages = new CopyOnWriteArrayList<>();

    @Override
    public void send(String text) {
      messages.add(text);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired JdbcTemplate jdbc;
  @Autowired InvariantAuditJob job;
  @Autowired RecordingNotifier notifier;

  private ApiFixture api;

  @BeforeEach
  void setUp() {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    api = new ApiFixture(mvc);
    notifier.messages.clear();
  }

  long contestWithTrade() throws Exception {
    long contestId = api.createContest("2026-12-05", 7L, true);
    long user = api.createUser();
    api.join(contestId, user);
    api.marketOrder(contestId, user, "SBSI", "BUY", 10).andExpect(status().isOk());
    return contestId;
  }

  List<String> alertsFor(long contestId) {
    return notifier.messages.stream().filter(m -> m.contains("대회 " + contestId + "\n")).toList();
  }

  @Test
  void 장부가_맞으면_알리지_않는다() throws Exception {
    long contestId = contestWithTrade();

    var reports = job.auditRunning();

    assertThat(reports).filteredOn(r -> r.contestId() == contestId).singleElement()
        .satisfies(r -> assertThat(r.ok()).isTrue());
    assertThat(alertsFor(contestId)).isEmpty();
  }

  @Test
  void 현금이_한_원이라도_어긋나면_그_대회와_규칙을_알린다() throws Exception {
    long contestId = contestWithTrade();
    jdbc.update("update participants set cash = cash + 1 where contest_id = ?", contestId);

    job.auditRunning();

    assertThat(alertsFor(contestId)).singleElement().asString()
        .contains("불변식 위반")
        .contains("I5_CASH_EQUALS_LEDGER 1건");
  }
}
