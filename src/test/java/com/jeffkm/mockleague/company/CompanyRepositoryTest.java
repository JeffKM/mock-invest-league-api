package com.jeffkm.mockleague.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JPA 계층만 띄우는 테스트. 메모리 DB로 바꾸지 않고(replace = NONE)
 * 실제 PostgreSQL 컨테이너 + Flyway 시드 위에서 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CompanyRepositoryTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired
  CompanyRepository companyRepository;

  @Test
  void 종목_코드로_회사를_찾는다() {
    Company company = companyRepository.findByTicker("SBSI").orElseThrow();

    assertThat(company.getName()).isEqualTo("솔빛실리콘");
    assertThat(company.getSector()).isEqualTo(Sector.SEMI_IT);
    assertThat(company.getVolatility()).isEqualTo(Volatility.MID);
    assertThat(company.getBasePrice()).isEqualTo(84_000L);
  }

  @Test
  void 없는_종목_코드는_빈_값이다() {
    assertThat(companyRepository.findByTicker("NONE")).isEmpty();
  }

  @Test
  void 섹터별로_4개씩_코드_순으로_나온다() {
    List<Company> bio = companyRepository.findBySectorOrderByTickerAsc(Sector.BIO);

    assertThat(bio).extracting(Company::getTicker)
        .containsExactly("CRGN", "DAPH", "PRCB", "SBDX");
  }
}
