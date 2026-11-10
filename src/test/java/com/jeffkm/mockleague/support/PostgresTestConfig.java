package com.jeffkm.mockleague.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 테스트 공용 PostgreSQL 컨테이너.
 * 테스트 클래스마다 @Container 를 두면 클래스마다 컨테이너를 새로 띄운다.
 * 빈으로 두고 @Import 하면 같은 설정의 테스트끼리 Spring 컨텍스트(와 컨테이너)를 재사용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>("postgres:16-alpine");
  }
}
