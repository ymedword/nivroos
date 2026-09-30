package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolResult;
import com.nivroos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** ShellTools 验收点：颗粒度文档 §4.2（ShellToolsTest 行）+ 契约 contracts/builtin-tools.md §2。 */
class ShellToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  private static JsonNode shellInput(String command) {
    return MAPPER.createObjectNode().put("command", command);
  }

  private static ShellTools shellToolsAllowing(String... commands) {
    return new ShellTools(
        new WhitelistSandbox(List.of(), List.of(commands), List.of()), Duration.ofSeconds(5));
  }

  // ---------------------------------------------------------------- 放行与失败语义

  @Test
  @DisplayName("白名单命令执行成功：stdout 进 ToolResult.content")
  void shell_whitelistedCommand_returnsStdout() {
    ToolResult result = shellToolsAllowing("echo").shell("echo hello");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("hello\n");
  }

  @Test
  @DisplayName("非白名单命令拒绝，且命令确实未启动（副作用文件不存在）")
  void shell_commandNotWhitelisted_rejectedAndNeverRuns() {
    Path marker = tempDir.resolve("started.txt");
    ShellTools tools = shellToolsAllowing("echo");

    assertThatThrownBy(() -> tools.shell("touch " + marker))
        .hasMessageContaining("command not allowed");

    assertThat(marker).doesNotExist();
  }

  @Test
  @DisplayName("非零退出：success=false，stderr 进 errorMessage")
  void shell_nonZeroExit_failsWithStderr() {
    ShellTools tools = shellToolsAllowing("bash", "ls");

    ToolResult result = tools.shell("ls /no/such/path-nivroos");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("No such file or directory");
  }

  @Test
  @DisplayName("超时终止且返回失败（不挂死），失败路径落 WARN 日志")
  void shell_timeout_killsProcessAndLogsWarning() {
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      ToolResult result = shellToolsAllowing("sleep").shell("sleep 5");

      assertThat(result.success()).isFalse();
      assertThat(result.errorMessage()).contains("超时");
    } finally {
      detachAppender(appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("timed out");
            });
  }

  @Test
  @DisplayName("空白名单 → 任何命令全拒（不静默放行）")
  void shell_emptyWhitelist_deniesEverything() {
    ShellTools tools =
        new ShellTools(
            new WhitelistSandbox(List.of(), List.of(), List.of()), Duration.ofSeconds(5));

    assertThatThrownBy(() -> tools.shell("echo hi"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("command not allowed");
  }

  // ---------------------------------------------------------------- 关键回归

  @Test
  @DisplayName("Shell 并发两路调用：输出不串（各自读到自己的 stdout）")
  void shell_concurrentCalls_outputsDoNotMix() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.scanAnnotated(
        new ShellTools(
            new WhitelistSandbox(List.of(), List.of("echo"), List.of()), Duration.ofSeconds(5)));
    NivroTool shell = registry.get("shell");

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ToolResult> a = executor.submit(() -> shell.execute(shellInput("echo AAA")));
      Future<ToolResult> b = executor.submit(() -> shell.execute(shellInput("echo BBB")));

      assertThat(a.get().content()).contains("AAA").doesNotContain("BBB");
      assertThat(b.get().content()).contains("BBB").doesNotContain("AAA");
    }
  }

  // ---------------------------------------------------------------- 大输出不假性超时

  @Test
  @DisplayName("大输出（超过管道缓冲区）不假性超时：重定向到文件而非管道")
  void shell_largeOutput_doesNotFalselyTimeOut() throws IOException {
    ShellTools tools = shellToolsAllowing("bash", "seq", "yes");
    Path script = Files.writeString(tempDir.resolve("big.sh"), "seq 1 20000");

    ToolResult result = tools.shell("bash " + script);

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("20000");
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ShellTools.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(ShellTools.class)).detachAppender(appender);
  }
}
