package com.nivroos.tool;

import com.nivroos.core.model.ToolResult;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置文件工具（技术方案 §6.2；契约 contracts/builtin-tools.md §1）。
 *
 * <p>Three tools — read / write / list — each enforcing the sandbox as its very first step, before
 * any filesystem call: a rejected path must leave nothing behind (FR-005). 业务性失败返回失败结果， 异常性失败（沙箱拒绝
 * / IO）抛异常，由既有 ToolExecutor 落失败审计。
 */
public class FileTools {

  private static final Logger log = LoggerFactory.getLogger(FileTools.class);

  private final Sandbox sandbox;

  public FileTools(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  /** 读取文件全文。 */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "path 已过 sanitizeForLog（CR/LF 剥离）；findsecbugs 不识别自定义消毒方法，异常对象仅作堆栈附加")
  @Tool(name = "read_file", description = "读取文本文件全文（路径须在文件白名单内）")
  public ToolResult readFile(@ToolParam(description = "要读取的文件路径") String path) {
    // 首步校验（宪法原则六）：拒绝时抛 SandboxViolationException，读动作不发生
    sandbox.enforce(new SandboxAction(SandboxAction.ActionType.FILE_READ, path));
    try {
      return new ToolResult(
          true, Files.readString(Path.of(path), StandardCharsets.UTF_8), null, false);
    } catch (IOException e) {
      // 不吞：WARN 留痕后上抛，由 ToolExecutor 落失败审计并回填对话上下文
      log.warn("read_file failed: path={}", sanitizeForLog(path), e);
      throw new UncheckedIOException("read_file failed: " + path, e);
    }
  }

  /** 覆盖写入文件（不创建父目录——目标目录缺失同样是可读的失败原因）。 */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "path 已过 sanitizeForLog（CR/LF 剥离）；findsecbugs 不识别自定义消毒方法，异常对象仅作堆栈附加")
  @Tool(name = "write_file", description = "写入文本文件（覆盖同名文件；路径须在文件白名单内）")
  public ToolResult writeFile(
      @ToolParam(description = "要写入的文件路径") String path,
      @ToolParam(description = "写入内容") String content) {
    if (content == null) {
      // 业务性失败：参数缺失返回失败结果，不抛异常（契约 §0.2）
      return new ToolResult(false, null, "write_file 需要 content 参数", false);
    }
    // 首步校验：越界写入必须连文件都不创建（契约 §1「越界时动作不得发生」）
    sandbox.enforce(new SandboxAction(SandboxAction.ActionType.FILE_WRITE, path));
    try {
      Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
      return new ToolResult(true, "已写入 " + path, null, false);
    } catch (IOException e) {
      log.warn("write_file failed: path={}", sanitizeForLog(path), e);
      throw new UncheckedIOException("write_file failed: " + path, e);
    }
  }

  /** 列出目录条目（逐行连接，排序保证输出稳定）。 */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "path 已过 sanitizeForLog（CR/LF 剥离）；findsecbugs 不识别自定义消毒方法，异常对象仅作堆栈附加")
  @Tool(name = "list_dir", description = "列出目录下的条目（路径须在文件白名单内）")
  public ToolResult listDir(@ToolParam(description = "要列出的目录路径") String path) {
    // 列目录按读档校验（契约 §1：FILE_READ 覆盖读与列）
    sandbox.enforce(new SandboxAction(SandboxAction.ActionType.FILE_READ, path));
    try (var entries = Files.list(Path.of(path))) {
      // 目录条目的名字必定存在；requireNonNull 只是把 SpotBugs 的可空路径分析钉住
      List<String> names =
          entries
              .map(entry -> Objects.requireNonNull(entry.getFileName()).toString())
              .sorted()
              .toList();
      if (names.isEmpty()) {
        return new ToolResult(true, "（空目录）", null, false);
      }
      return new ToolResult(true, String.join("\n", names), null, false);
    } catch (IOException e) {
      log.warn("list_dir failed: path={}", sanitizeForLog(path), e);
      throw new UncheckedIOException("list_dir failed: " + path, e);
    }
  }

  /** 日志参数 CRLF 消毒：路径来自模型输出，防止日志行注入（findsecbugs，同 HttpTools 先例）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
