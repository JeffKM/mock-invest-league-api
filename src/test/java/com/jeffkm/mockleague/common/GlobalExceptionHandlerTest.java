package com.jeffkm.mockleague.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** 5주차② k6: DB 커넥션을 못 얻으면 500 이 아니라 503 + Retry-After 로 응답한다. */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void 트랜잭션을_못_열면_503과_Retry_After() {
    var cause = new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 3001ms");
    ResponseEntity<ApiResponse<Void>> res =
        handler.handleBusy(new CannotCreateTransactionException("Could not open JPA EntityManager", cause));

    assertThat(res.getStatusCode().value()).isEqualTo(503);
    assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(res.getBody().error().code()).isEqualTo("SERVER_BUSY");
  }

  @Test
  void JDBC_커넥션을_못_얻어도_503() {
    ResponseEntity<ApiResponse<Void>> res = handler.handleBusy(
        new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", new SQLTransientConnectionException("timeout")));

    assertThat(res.getStatusCode().value()).isEqualTo(503);
  }
}
