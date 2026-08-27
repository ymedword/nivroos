package com.nivroos.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * 统一 API 响应信封（CLAUDE.md / 技术方案定死的规范：code/message/data/timestamp）。
 *
 * <p>所有 /api/v1 端点一律返回本结构；错误场景由 {@link GlobalExceptionHandler} 统一包装，业务代码不直接拼接错误 JSON。code 语义镜像 HTTP
 * 状态码（见 {@link ErrorCode}）。
 *
 * @param <T> 业务数据类型
 * @param code 状态码，与 HTTP 状态码语义一致
 * @param message 人类可读信息
 * @param data 业务数据，成功时返回
 * @param timestamp 服务端时间戳（ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(
        ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data, Instant.now());
  }

  public static <T> ApiResponse<T> error(ErrorCode errorCode) {
    return new ApiResponse<>(errorCode.code(), errorCode.message(), null, Instant.now());
  }

  public static <T> ApiResponse<T> error(ErrorCode errorCode, String detail) {
    return new ApiResponse<>(errorCode.code(), detail, null, Instant.now());
  }
}
