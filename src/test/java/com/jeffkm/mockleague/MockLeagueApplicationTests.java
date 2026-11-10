package com.jeffkm.mockleague;

import static org.assertj.core.api.Assertions.assertThat;

import com.jeffkm.mockleague.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 스키마(Flyway)가 실제 PostgreSQL에 적용되고, 모든 엔티티가 스키마 검증(validate)을 통과해 앱이 뜨는지 확인. */
@SpringBootTest
@Import(PostgresTestConfig.class)
class MockLeagueApplicationTests {

  @Autowired
  JdbcTemplate jdbc;

  @Test
  void 앱이_뜨고_가상_상장사_24개가_들어간다() {
    Integer count = jdbc.queryForObject("select count(*) from companies", Integer.class);
    assertThat(count).isEqualTo(24);
  }
}
