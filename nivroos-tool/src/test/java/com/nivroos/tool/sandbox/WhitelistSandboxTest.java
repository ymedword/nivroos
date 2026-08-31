package com.nivroos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 白名单沙箱验收点：颗粒度文档 §4.2（FR-003）。 */
class WhitelistSandboxTest {

  private final WhitelistSandbox sandbox =
      new WhitelistSandbox(List.of("wttr.in", "*.example.com"));

  @Test
  @DisplayName("域名精确匹配通过")
  void exactDomainMatch_allowed() {
    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.HTTP_REQUEST, "https://wttr.in/beijing")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("通配符匹配裸域名与子域名")
  void wildcardDomain_allowsBareAndSubdomains() {
    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.HTTP_REQUEST, "https://example.com/a")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.HTTP_REQUEST, "https://api.example.com/a")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("未允许的域名被拒绝且消息含域名")
  void disallowedDomain_rejectedWithHost() {
    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, "https://evil.com/x")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("evil.com");
  }

  @Test
  @DisplayName("非法 URL 被拒绝")
  void invalidUrl_rejected() {
    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, "not-a-url")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("文件/Shell 白名单留 US-4：当前构建明确拒绝")
  void fileAndShellActions_rejectedUntilUs4() {
    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.FILE_READ, "/tmp/a.txt")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("US-4");
  }
}
