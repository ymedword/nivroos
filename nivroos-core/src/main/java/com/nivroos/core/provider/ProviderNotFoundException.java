package com.nivroos.core.provider;

import java.util.Set;

/**
 * 供应商未注册异常（FR-010）。
 *
 * <p>Thrown when a Profile references a provider that is not registered; the message must carry the
 * missing name and the list of available ones.
 */
public class ProviderNotFoundException extends ProviderException {

  public ProviderNotFoundException(String providerName, Set<String> available) {
    super("Provider not found: " + providerName + " (available: " + available + ")");
  }
}
