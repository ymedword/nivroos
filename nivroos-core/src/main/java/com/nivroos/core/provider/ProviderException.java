package com.nivroos.core.provider;

/**
 * 供应商调用异常基类。
 *
 * <p>Base exception for provider failures; never swallowed - callers either record it in audit/log
 * or rethrow (module-dev gate H1/H5).
 */
public class ProviderException extends RuntimeException {

  public ProviderException(String message) {
    super(message);
  }

  public ProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}
