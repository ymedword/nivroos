package com.nivroos.core.provider;

/**
 * 供应商调用失败异常（FR-006）。
 *
 * <p>Thrown when the upstream call fails (network error / timeout / vendor error); no automatic
 * switch or silent retry in the core stage.
 */
public class ProviderCallException extends ProviderException {

  public ProviderCallException(String provider, String model, String reason, Throwable cause) {
    super(
        "Provider call failed: provider=" + provider + ", model=" + model + ", reason=" + reason,
        cause);
  }
}
