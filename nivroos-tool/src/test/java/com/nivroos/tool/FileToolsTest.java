package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nivroos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** FileTools 验收点：颗粒度文档 §4.2（FileToolsTest 行）+ 契约 contracts/builtin-tools.md §1。 */
class FileToolsTest {

  @TempDir Path tempDir;

  /** 白名单 = 工作区子目录；越界用例以它的兄弟目录作对照。 */
  private Path workspace;

  private FileTools fileTools;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectories(tempDir.resolve("ws"));
    fileTools =
        new FileTools(new WhitelistSandbox(List.of(workspace.toString()), List.of(), List.of()));
  }

  // ---------------------------------------------------------------- 放行路径

  @Test
  @DisplayName("read_file 读到文件全文")
  void readFile_returnsFileContent() throws IOException {
    Path file = workspace.resolve("note.md");
    Files.writeString(file, "第一行\n第二行", StandardCharsets.UTF_8);

    var result = fileTools.readFile(file.toString());

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("第一行\n第二行");
  }

  @Test
  @DisplayName("write_file 落盘并返回成功文案")
  void writeFile_writesContentToDisk() {
    Path target = workspace.resolve("out.txt");

    var result = fileTools.writeFile(target.toString(), "hello");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains(target.toString());
    assertThat(target).content(StandardCharsets.UTF_8).isEqualTo("hello");
  }

  @Test
  @DisplayName("write_file 覆盖同名文件（不是追加）")
  void writeFile_overwritesExistingFile() throws IOException {
    Path target = Files.writeString(workspace.resolve("out.txt"), "old");

    fileTools.writeFile(target.toString(), "new");

    assertThat(target).content(StandardCharsets.UTF_8).isEqualTo("new");
  }

  @Test
  @DisplayName("list_dir 列出条目（逐行连接、排序稳定）")
  void listDir_returnsSortedEntries() throws IOException {
    Files.writeString(workspace.resolve("b.txt"), "b");
    Files.writeString(workspace.resolve("a.txt"), "a");
    Files.createDirectories(workspace.resolve("sub"));

    var result = fileTools.listDir(workspace.toString());

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("a.txt\nb.txt\nsub");
  }

  @Test
  @DisplayName("list_dir 空目录给明确文案（不返回空串）")
  void listDir_emptyDirectory_returnsExplicitText() {
    var result = fileTools.listDir(workspace.toString());

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isNotBlank().contains("空目录");
  }

  // ---------------------------------------------------------------- 沙箱拒绝

  @Test
  @DisplayName("白名单外路径：读 / 列 / 写一律被拒（异常冒泡到 ToolExecutor 路径）")
  void enforce_pathsOutsideWhitelist_rejectedForAllThreeTools() {
    String outside = tempDir.resolve("outside.txt").toString();

    assertThatThrownBy(() -> fileTools.readFile(outside))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("path not allowed");
    assertThatThrownBy(() -> fileTools.listDir(outside))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("path not allowed");
    assertThatThrownBy(() -> fileTools.writeFile(outside, "x"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("path not allowed");
  }

  @Test
  @DisplayName("越界 write_file 后目标文件确实不存在（被拒的动作不得发生）")
  void writeFile_outsideWhitelist_leavesNoFileBehind() {
    Path escaped = tempDir.resolve("escaped.txt");

    assertThatThrownBy(() -> fileTools.writeFile(escaped.toString(), "leak"))
        .isInstanceOf(RuntimeException.class);

    assertThat(escaped).doesNotExist();
  }

  @Test
  @DisplayName("路径穿越 ../ 越出白名单必拒，且目标文件未创建")
  void writeFile_pathTraversalOutsideWhitelist_rejected() {
    Path escaped = tempDir.resolve("traversed.txt");
    String sneaky = workspace.resolve("../traversed.txt").toString();

    assertThatThrownBy(() -> fileTools.writeFile(sneaky, "leak"))
        .isInstanceOf(RuntimeException.class);

    assertThat(escaped).doesNotExist();
  }

  // ---------------------------------------------------------------- 失败不吞

  @Test
  @DisplayName("read_file 目标不存在：异常上抛且落 WARN 日志（不吞）")
  void readFile_missingFile_throwsAndLogsWarning() {
    Path missing = workspace.resolve("nope.txt");
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      assertThatThrownBy(() -> fileTools.readFile(missing.toString()))
          .isInstanceOf(UncheckedIOException.class)
          .hasMessageContaining("nope.txt");
    } finally {
      detachAppender(appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("read_file failed");
            });
  }

  @Test
  @DisplayName("list_dir 目标不是目录：异常上抛且落 WARN 日志")
  void listDir_onRegularFile_throwsAndLogsWarning() throws IOException {
    Path file = Files.writeString(workspace.resolve("plain.txt"), "x");
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      assertThatThrownBy(() -> fileTools.listDir(file.toString()))
          .isInstanceOf(UncheckedIOException.class);
    } finally {
      detachAppender(appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("list_dir failed");
            });
  }

  @Test
  @DisplayName("write_file 目标目录不存在：异常上抛（不静默成功）")
  void writeFile_missingParentDirectory_throws() {
    Path target = workspace.resolve("no-such-dir/out.txt");

    assertThatThrownBy(() -> fileTools.writeFile(target.toString(), "x"))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  @DisplayName("write_file 缺 content：返回失败结果而非异常（业务性失败语义）")
  void writeFile_missingContent_returnsFailureResult() {
    var result = fileTools.writeFile(workspace.resolve("out.txt").toString(), null);

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("content");
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(FileTools.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(FileTools.class)).detachAppender(appender);
  }
}
