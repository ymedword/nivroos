package com.nivroos.web.api;

/** API 错误码枚举。code 语义镜像 HTTP 状态码，两者保持一致以降低集成方理解成本。 核心阶段错误面收敛到：参数类 400、未命中 404、兜底 500、超时 504。 */
public enum ErrorCode {
  SUCCESS(200, "成功"),
  BAD_REQUEST(400, "请求参数错误"),
  NOT_FOUND(404, "资源不存在"),
  INTERNAL_ERROR(500, "服务器内部错误"),
  TIMEOUT(504, "调用超时");

  private final int code;
  private final String message;

  ErrorCode(int code, String message) {
    this.code = code;
    this.message = message;
  }

  public int code() {
    return code;
  }

  public String message() {
    return message;
  }
}
