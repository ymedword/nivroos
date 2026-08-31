package com.nivroos.tool.sandbox;

import java.net.URI;
import java.util.List;

/**
 * 应用层白名单沙箱（宪法原则六：核心阶段唯一实现）。
 *
 * <p>US-2 delivers the HTTP domain whitelist only; FILE_READ / FILE_WRITE / SHELL_COMMAND
 * enforcement lands in US-4 on the same implementation class, the interface stays unchanged.
 * Wildcard patterns ("*.wttr.in") match the bare domain and all subdomains.
 */
public class WhitelistSandbox implements Sandbox {

  private final List<String> allowedDomains;

  public WhitelistSandbox(List<String> allowedDomains) {
    this.allowedDomains = List.copyOf(allowedDomains == null ? List.of() : allowedDomains);
  }

  @Override
  public void enforce(SandboxAction action) {
    switch (action.type()) {
      case HTTP_REQUEST -> checkHttpUrl(action.target());
      // 文件/Shell 白名单在 US-4 补全（同一实现类扩展，接口不变）
      case FILE_READ, FILE_WRITE, SHELL_COMMAND ->
          throw new SandboxViolationException(
              action.type() + " enforcement lands in US-4 (this build allows HTTP only)");
    }
  }

  /** 解析 host 后做通配符匹配。 */
  private void checkHttpUrl(String target) {
    String host;
    try {
      host = URI.create(target).getHost();
    } catch (Exception e) {
      throw new SandboxViolationException("invalid URL: " + target);
    }
    if (host == null) {
      throw new SandboxViolationException("no host in URL: " + target);
    }
    if (allowedDomains.stream().anyMatch(pattern -> matches(pattern, host))) {
      return;
    }
    throw new SandboxViolationException("domain not allowed: " + host);
  }

  private static boolean matches(String pattern, String host) {
    if (pattern.startsWith("*.")) {
      String suffix = pattern.substring(2);
      return host.equals(suffix) || host.endsWith("." + suffix);
    }
    return pattern.equals(host);
  }
}
