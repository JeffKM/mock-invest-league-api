package com.jeffkm.mockleague.common;

/**
 * 모든 API 응답의 공통 형식.
 * 성공: { "success": true, "data": ..., "error": null }
 * 실패: { "success": false, "data": null, "error": { "code": ..., "message": ... } }
 */
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

  public record ErrorBody(String code, String message) {}

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static ApiResponse<Void> fail(ErrorCode code, String message) {
    return new ApiResponse<>(false, null, new ErrorBody(code.name(), message));
  }
}
