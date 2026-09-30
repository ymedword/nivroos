package com.nivroos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 白名单沙箱验收点：颗粒度文档 §4.2（FR-003 / FR-005）。 */
class WhitelistSandboxTest {

  @TempDir Path tempDir;

  /** 文件白名单根 = 临时目录下的 ws 子目录；命令白名单 echo / git；域名白名单两条。 */
  private WhitelistSandbox whitelistSandbox;

  private Path workspace;

  @BeforeEach
  void setUp() {
    workspace = tempDir.resolve("ws");
    whitelistSandbox =
        new WhitelistSandbox(
            List.of(workspace.toString()),
            List.of("echo", "git"),
            List.of("wttr.in", "*.example.com"));
  }

  // ---------- 域名档（US-2 回归） ----------

  @Test
  @DisplayName("域名精确匹配通过")
  void exactDomainMatch_allowed() {
    assertThatCode(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.HTTP_REQUEST, "https://wttr.in/beijing")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("通配符匹配裸域名与子域名")
  void wildcardDomain_allowsBareAndSubdomains() {
    assertThatCode(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.HTTP_REQUEST, "https://example.com/a")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.HTTP_REQUEST, "https://api.example.com/a")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("通配符边界：*.example.com 不命中文本前缀相似的 evil-example.com（点号边界）")
  void wildcardDomain_doesNotMatchTextualPrefixSibling() {
    assertThatThrownBy(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.HTTP_REQUEST, "https://evil-example.com/hook")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("evil-example.com");
  }

  @Test
  @DisplayName("未允许的域名被拒绝且消息含域名")
  void disallowedDomain_rejectedWithHost() {
    assertThatThrownBy(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, "https://evil.com/x")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("evil.com");
  }

  @Test
  @DisplayName("非法 URL 被拒绝")
  void invalidUrl_rejected() {
    assertThatThrownBy(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, "not-a-url")))
        .isInstanceOf(SandboxViolationException.class);
  }

  // ---------- 文件档 ----------

  @Test
  @DisplayName("路径穿越：../ 规范化后越出白名单必拒，白名单内相对路径放行")
  void enforce_pathTraversalOutsideWhitelist_rejected() {
    WhitelistSandbox sandbox = new WhitelistSandbox(List.of("/tmp/ws"), List.of(), List.of());

    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.FILE_READ, "/tmp/ws/agents/a/AGENT.md")))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.FILE_READ, "/tmp/ws/agents/../../etc/passwd")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("白名单内路径放行、白名单根自身放行（读取与写入同档）")
  void filePath_insideWhitelist_allowed() {
    Path inside = workspace.resolve("agents/a/AGENT.md");

    assertThatCode(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.FILE_READ, inside.toString())))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.FILE_WRITE, inside.toString())))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.FILE_WRITE, workspace.toString())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("越界写入：../ 穿越被拒，且越界目标文件确实不存在（危险动作没有发生）")
  void write_crossBoundary_rejectedAndNothingLanded() {
    Path escaped = tempDir.resolve("escaped.txt"); // workspace/../escaped.txt 规范化后的落点

    assertThatThrownBy(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.FILE_WRITE, workspace + "/../escaped.txt")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("escaped.txt");

    // 沙箱层只做校验、不触盘；越界内容确实没落盘（真实 write_file 的落盘证明见 FileToolsTest）
    assertThat(Files.exists(escaped)).isFalse();
  }

  @Test
  @DisplayName("路径白名单为空 → 全部拒绝（不是不校验）")
  void filePath_emptyWhitelist_deniesEverything() {
    WhitelistSandbox empty = new WhitelistSandbox(List.of(), List.of("echo"), List.of());

    assertThatThrownBy(
            () ->
                empty.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.FILE_WRITE, tempDir.resolve("a.txt").toString())))
        .isInstanceOf(SandboxViolationException.class);
  }

  // ---------- Shell 档 ----------

  @Test
  @DisplayName("命令首 token 命中白名单 → 放行（参数不参与校验）")
  void shellCommand_firstTokenAllowed_passes() {
    assertThatCode(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.SHELL_COMMAND, "git status --short")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("命令首 token 未命中白名单 → 拒绝且消息含该 token")
  void shellCommand_notWhitelisted_rejected() {
    assertThatThrownBy(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.SHELL_COMMAND, "rm -rf /tmp/ws")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("rm");
  }

  @Test
  @DisplayName("命令前导空白按 trim 处理后比对 → 放行")
  void shellCommand_leadingWhitespaceTrimmed() {
    assertThatCode(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.SHELL_COMMAND, "   echo hi")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("命令比对大小写敏感（Linux 语义）→ 大写 ECHO 拒绝")
  void shellCommand_caseSensitive_rejected() {
    assertThatThrownBy(
            () ->
                whitelistSandbox.enforce(
                    new SandboxAction(SandboxAction.ActionType.SHELL_COMMAND, "ECHO hi")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("命令白名单为空 → 全部拒绝（不是不校验）")
  void shellCommand_emptyWhitelist_deniesEverything() {
    WhitelistSandbox empty = new WhitelistSandbox(List.of("/tmp"), List.of(), List.of());

    assertThatThrownBy(
            () ->
                empty.enforce(new SandboxAction(SandboxAction.ActionType.SHELL_COMMAND, "echo hi")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("HTTP 域名白名单为空 → 全部拒绝（不是不校验）")
  void httpDomain_emptyWhitelist_deniesEverything() {
    WhitelistSandbox empty = new WhitelistSandbox(List.of(), List.of(), List.of());

    assertThatThrownBy(
            () ->
                empty.enforce(
                    new SandboxAction(
                        SandboxAction.ActionType.HTTP_REQUEST, "https://wttr.in/beijing")))
        .isInstanceOf(SandboxViolationException.class);
  }
}
