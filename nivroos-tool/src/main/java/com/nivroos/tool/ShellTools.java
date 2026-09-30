package com.nivroos.tool;

import com.nivroos.core.model.ToolResult;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置 Shell 工具（技术方案 §6.2；契约 contracts/builtin-tools.md §2）。
 *
 * <p>Runs one command through POSIX {@code bash -c} with a hard timeout. Sandbox enforcement covers
 * the first token only (FR-004) — arguments are deliberately not inspected, so a whitelisted
 * command can still reach anything its arguments point at; the tier is a "劝阻级防线" (spec
 * Assumptions), not a jail.
 *
 * <p>输出采集走临时文件而非管道：管道缓冲区写满会让子进程阻塞、waitFor 假性超时，而读取两个 管道又需要额外线程。Redirecting streams to files keeps
 * the call synchronous and deadlock-free.
 */
public class ShellTools {

  private static final Logger log = LoggerFactory.getLogger(ShellTools.class);

  private final Sandbox sandbox;
  private final Duration timeout;

  public ShellTools(Sandbox sandbox, Duration timeout) {
    this.sandbox = sandbox;
    this.timeout = timeout;
  }

  /**
   * 执行命令：白名单首 token 校验 → {@code bash -c} → 超时强杀。
   *
   * <p>Failure semantics: a non-zero exit and a timeout both return {@code success=false} with the
   * reason in {@code errorMessage} (stderr preferred), so the model can see why and adapt; sandbox
   * rejections and IO errors throw instead, leaving the audit to the existing ToolExecutor path.
   */
  @SuppressFBWarnings(
      value = {"COMMAND_INJECTION", "CRLF_INJECTION_LOGS"},
      justification =
          "命令首 token 先过 WhitelistSandbox 才构造 ProcessBuilder（enforce 在 start 之前）；参数按 FR-004 有意不校验，"
              + "该档定位是劝阻级防线而非逃逸防护；日志 command 已过 sanitizeForLog（CR/LF 剥离），findsecbugs 不识别自定义消毒方法")
  @Tool(name = "shell", description = "在本机执行 shell 命令（命令首词须在白名单内，带执行超时）")
  public ToolResult shell(@ToolParam(description = "要执行的 shell 命令") String command) {
    // 首步校验（宪法原则六）：拒绝时命令不得启动
    sandbox.enforce(new SandboxAction(SandboxAction.ActionType.SHELL_COMMAND, command));

    Path stdoutFile = null;
    Path stderrFile = null;
    try {
      stdoutFile = Files.createTempFile("nivroos-shell-out", ".txt");
      stderrFile = Files.createTempFile("nivroos-shell-err", ".txt");
      Process process =
          new ProcessBuilder("bash", "-c", command)
              .redirectOutput(stdoutFile.toFile())
              .redirectError(stderrFile.toFile())
              .start();

      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        log.warn("shell timed out: command={}", sanitizeForLog(command));
        return new ToolResult(false, null, "命令超时（" + timeout.toSeconds() + " 秒）已强制终止", false);
      }

      String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
      String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        log.warn("shell failed: command={}, exitCode={}", sanitizeForLog(command), exitCode);
        String error = stderr.isBlank() ? "命令退出码 " + exitCode + "（无 stderr 输出）" : stderr;
        return new ToolResult(false, stdout.isBlank() ? null : stdout, error, false);
      }
      return new ToolResult(true, stdout, null, false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("shell interrupted: command={}", sanitizeForLog(command), e);
      throw new IllegalStateException("shell interrupted: " + command, e);
    } catch (IOException e) {
      log.warn("shell failed to start: command={}", sanitizeForLog(command), e);
      throw new IllegalStateException("shell failed to start: " + command, e);
    } finally {
      deleteQuietly(stdoutFile);
      deleteQuietly(stderrFile);
    }
  }

  /** 临时文件清理失败不影响结果（下一次同命令会重新建文件），但留痕便于排查。 */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "path 已过 sanitizeForLog（CR/LF 剥离）；findsecbugs 不识别自定义消毒方法，异常对象仅作堆栈附加")
  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("shell temp file cleanup failed: path={}", sanitizeForLog(String.valueOf(file)), e);
    }
  }

  /** 日志参数 CRLF 消毒：命令来自模型输出，防止日志行注入（findsecbugs，同 HttpTools 先例）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
