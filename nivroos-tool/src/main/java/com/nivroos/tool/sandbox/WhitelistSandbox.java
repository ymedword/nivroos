package com.nivroos.tool.sandbox;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * 应用层白名单沙箱（宪法原则六：核心阶段唯一实现）。
 *
 * <p>US-2 delivered the HTTP domain whitelist only; US-4 completes the FILE_READ / FILE_WRITE /
 * SHELL_COMMAND tiers on the same implementation class — the {@link Sandbox} interface itself stays
 * unchanged, which is what keeps a future container/microVM backend a drop-in replacement.
 *
 * <p>One rule applies to all three tiers: an empty list denies everything, it never means "no
 * check" (FR-005). Wildcard domain patterns ("*.wttr.in") match the bare domain and all subdomains.
 *
 * <p>定位提醒：这一层是"劝阻级防线"——防模型犯傻误操作，不防蓄意绕过（spec Assumptions）。
 */
public class WhitelistSandbox implements Sandbox {

  private final List<String> allowedPaths;
  private final List<String> allowedCommands;
  private final List<String> allowedDomains;

  /**
   * 三档白名单构造（US-2 一参构造的扩展，见颗粒度文档 §3.2 前序改造点 1）。
   *
   * @param allowedPaths 文件工具路径白名单（空 = 全部拒绝）
   * @param allowedCommands Shell 命令首 token 白名单（空 = 全部拒绝）
   * @param allowedDomains HTTP 域名白名单，含通配符（空 = 全部拒绝）
   */
  public WhitelistSandbox(
      List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains) {
    this.allowedPaths = copyOf(allowedPaths);
    this.allowedCommands = copyOf(allowedCommands);
    this.allowedDomains = copyOf(allowedDomains);
  }

  @Override
  public void enforce(SandboxAction action) {
    switch (action.type()) {
      case FILE_READ, FILE_WRITE -> checkFilePath(action.target());
      case SHELL_COMMAND -> checkShellCommand(action.target());
      case HTTP_REQUEST -> checkHttpUrl(action.target());
    }
  }

  /**
   * 文件路径白名单：目标与白名单项各自绝对化 + 规范化后做前缀比对。
   *
   * <p>Both sides are absolutised from the process working directory and normalized before the
   * comparison, so "../" traversal and other equivalent spellings are collapsed to one form. Real
   * symlink targets are deliberately NOT resolved: that would add IO failure branches and a
   * check/use race, and it would change the meaning of "a whitelisted path that links outward".
   */
  private void checkFilePath(String target) {
    if (target == null || target.isBlank()) {
      throw new SandboxViolationException("empty file path");
    }
    Path candidate = normalize(target);
    boolean allowed = allowedPaths.stream().anyMatch(entry -> isUnder(candidate, normalize(entry)));
    if (!allowed) {
      throw new SandboxViolationException("path not allowed: " + target);
    }
  }

  /**
   * Shell 命令白名单：只比对命令的**首个 token**，逐字且大小写敏感（Linux 语义）。
   *
   * <p>Only the first whitespace-delimited token is checked — arguments are not inspected (FR-004),
   * so a command may still reach anything its arguments point at. Leading/trailing whitespace is
   * trimmed first; the comparison is case sensitive because the deployment target is Linux.
   */
  private void checkShellCommand(String target) {
    if (target == null || target.isBlank()) {
      throw new SandboxViolationException("empty shell command");
    }
    String firstToken = target.trim().split("\\s+", 2)[0];
    if (!allowedCommands.contains(firstToken)) {
      throw new SandboxViolationException("command not allowed: " + firstToken);
    }
  }

  /** 解析 host 后做通配符匹配（US-2 已交付，行为不变）。 */
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

  private static List<String> copyOf(List<String> values) {
    return List.copyOf(values == null ? List.of() : values);
  }

  private static Path normalize(String raw) {
    return Path.of(raw).toAbsolutePath().normalize();
  }

  /**
   * 前缀比对按路径**元素**逐段进行：`/a/bc` 不会命中白名单根 `/a/b`（无需手工补分隔符）， 白名单根自身则直接命中。
   *
   * <p>Path.startsWith compares whole name elements, so the separator boundary is inherent: a
   * sibling directory that merely shares a textual prefix is not admitted, and the root itself is.
   */
  private static boolean isUnder(Path candidate, Path root) {
    return candidate.startsWith(root);
  }

  private static boolean matches(String pattern, String host) {
    if (pattern.startsWith("*.")) {
      String suffix = pattern.substring(2);
      return host.equals(suffix) || host.endsWith("." + suffix);
    }
    return pattern.equals(host);
  }
}
