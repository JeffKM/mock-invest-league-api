package com.jeffkm.mockleague;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 스키마(Flyway)가 실제 PostgreSQL에 적용되고 앱이 뜨는지 확인. 도커가 켜져 있어야 한다. */
@SpringBootTest
@Testcontainers
class MockLeagueApplicationTests {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired
  JdbcTemplate jdbc;

  @Test
  void 앱이_뜨고_가상_상장사_24개가_들어간다() {
    Integer count = jdbc.queryForObject("select count(*) from companies", Integer.class);
    assertThat(count).isEqualTo(24);
  }
}
