package com.jeffkm.mockleague.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeffkm.mockleague.support.ApiFixture;
import com.jeffkm.mockleague.support.MutableClock;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** 7주차⑤ 새 틱의 순위 스냅샷을 찍으면 그 대회의 시세·순위가 한 메시지로 나간다. 같은 틱을 다시 찍으면 나가지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class, TickBroadcastTest.RecordingConfig.class})
class TickBroadcastTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class RecordingConfig {
    @Bean
    @Primary
    RecordingPublisher recordingPublisher() {
      return new RecordingPublisher();
    }
  }

  record Sent(long contestId, String json) {}

  static class RecordingPublisher implements TickPublisher {
    final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void publish(long contestId, String json) {
      sent.add(new Sent(contestId, json));
    }
  }

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired RecordingPublisher publisher;
  @Autowired ObjectMapper json;

  @Test
  void 새_틱의_스냅샷을_찍으면_시세와_순위를_보내고_같은_틱은_다시_보내지_않는다() throws Exception {
    clock.set(TestClockConfig.MARKET_OPEN_TIME);
    ApiFixture api = new ApiFixture(mvc);
    long contestId = api.createContest("2026-12-05", 7L, true);
    long user = api.createUser();
    api.join(contestId, user);
    api.marketOrder(contestId, user, "SBSI", "BUY", 10).andExpect(status().isOk());

    mvc.perform(post("/api/admin/contests/{id}/ranking-snapshots", contestId)).andExpect(status().isOk());
    mvc.perform(post("/api/admin/contests/{id}/ranking-snapshots", contestId)).andExpect(status().isOk());

    List<Sent> mine = publisher.sent.stream().filter(s -> s.contestId() == contestId).toList();
    assertThat(mine).hasSize(1);
    JsonNode message = json.readTree(mine.get(0).json());
    assertThat(message.get("contestId").asLong()).isEqualTo(contestId);
    assertThat(message.get("prices").get("SBSI").asLong()).isPositive();
    assertThat(message.get("prices").size()).isEqualTo(24);
    assertThat(message.get("ranking").get("entries")).hasSize(1);
  }
}
