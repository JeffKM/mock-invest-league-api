package com.jeffkm.mockleague.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

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

  @Test
  void 쿼리_도중_DB가_꺼져_롤백까지_실패해도_503() {
    // 6주차⑭ P9: RDS 재부팅 때 실제로 본 모양. 관리자 종료(57P01) → Hibernate 연결 예외 → 롤백 실패로 감싸임
    var admin = new SQLException("FATAL: terminating connection due to administrator command", "57P01");
    var jdbc = new JDBCConnectionException("Unable to commit against JDBC Connection", admin);
    var rollback = new TransactionSystemException("Could not roll back JPA transaction", new JpaSystemException(new RuntimeException(jdbc)));

    ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpected(rollback);

    assertThat(res.getStatusCode().value()).isEqualTo(503);
    assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(res.getBody().error().code()).isEqualTo("SERVER_BUSY");
  }

  @Test
  void 보내는_도중_연결이_끊긴_IO_오류도_503() {
    var io = new SQLException("An I/O error occurred while sending to the backend.", "08006");
    assertThat(handler.handleUnexpected(new RuntimeException(io)).getStatusCode().value()).isEqualTo(503);
  }

  @Test
  void DB와_상관없는_예외는_그대로_500() {
    var res = handler.handleUnexpected(new IllegalStateException("버그", new SQLException("문법 오류", "42601")));
    assertThat(res.getStatusCode().value()).isEqualTo(500);
    assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
  }
}
