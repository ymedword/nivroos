package com.nivroos.web.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * 全局异常处理器：所有 /api/v1 异常统一包装为 {@link ApiResponse} 信封。
 *
 * <p>核心阶段无认证、无限流，异常面收敛到：参数类 400、兜底 500。 日志如实记录异常堆栈（审计要求），响应体不泄漏堆栈细节。 US-5 落地业务端点时，按需补充
 * 404（NoSuchElement 类）与 504（60 秒超时）映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "sanitize() 已去除消息中的 CR/LF（防日志伪造），检测器无法识别该清理方法")
  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MissingServletRequestParameterException.class,
    HttpMessageNotReadableException.class
  })
  public ApiResponse<Void> handleBadRequest(Exception ex, WebRequest request) {
    log.warn(
        "Bad request: {} {} - {}",
        request.getDescription(false),
        ex.getClass().getSimpleName(),
        sanitize(ex.getMessage()));
    return ApiResponse.error(ErrorCode.BAD_REQUEST);
  }

  /** 日志防注入：去掉消息中的 CR/LF（防日志伪造），异常消息可能来自请求体。 */
  private static String sanitize(String message) {
    return message == null ? "" : message.replaceAll("[\\r\\n]", " ");
  }

  @ExceptionHandler(Exception.class)
  public ApiResponse<Void> handleUnknown(Exception ex) {
    log.error("Unhandled exception", ex);
    return ApiResponse.error(ErrorCode.INTERNAL_ERROR);
  }
}
