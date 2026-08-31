package com.nivroos.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;

/**
 * {@code nivroos init} —— 工作区初始化（技术方案 §8.1）。
 *
 * <p>Lightweight command: pure file operations, no Spring context. Idempotent: existing directories
 * and files are never overwritten, missing entries are created (contracts/cli-commands.md).
 */
@Command(name = "init", description = "在当前目录创建 .nivroos/ 工作区（幂等，不覆盖已存在文件）")
public class InitCommand implements Runnable {

  private static final List<String> DIRECTORIES =
      List.of("agents", "skills", "memory", "sessions", "logs");

  private static final List<String> BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md");

  @Override
  public void run() {
    Path workspace = Path.of(".nivroos");
    try {
      Files.createDirectories(workspace);
      for (String dir : DIRECTORIES) {
        Files.createDirectories(workspace.resolve(dir));
      }
      for (String file : BOOTSTRAP_FILES) {
        createIfAbsent(workspace.resolve(file), "# " + file + "\n\n（在此填写，随 system prompt 注入）\n");
      }
      createIfAbsent(
          workspace.resolve("memory").resolve("MEMORY.md"), "# MEMORY.md\n\n## 核心记忆\n\n## 归档记忆\n");
      createIfAbsent(
          workspace.resolve("mcp_servers.yaml"), "# MCP server 配置（name/transport/command/env）\n");
      System.out.println("工作区已就绪: " + workspace.toAbsolutePath());
    } catch (IOException e) {
      throw new IllegalStateException("工作区初始化失败: " + workspace.toAbsolutePath(), e);
    }
  }

  private static void createIfAbsent(Path file, String content) throws IOException {
    if (Files.exists(file)) {
      return; // 幂等：已存在不覆盖
    }
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }
}
