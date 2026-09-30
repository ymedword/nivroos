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

  /**
   * {@code .nivroos/mcp_servers.yaml} 模板（键名是已定字面量，契约 contracts/mcp-and-notify.md §1）。
   *
   * <p>{@code servers: []} 是"未配置"的显式形态，示例整段注释掉——模板一旦写出未注释的 server 条目，加载端就会真去起子进程（InitCommandTest
   * 钉住这一点）。MCP 的 env 只允许 {@code ${ENV_VAR}} 占位，示例里不放任何真实凭证。
   */
  static final String MCP_CONFIG_TEMPLATE =
      """
      # MCP server 配置（核心阶段只支持 stdio；改动需重启生效）
      # env 只允许 ${ENV_VAR} 占位，明文会被拒绝；值是凭证，不要入库、不要写进文档。
      servers: []
      # 示例（去掉行首 # 即启用）：
      # servers:
      #   - name: github-mcp
      #     transport: stdio
      #     command: "npx -y @modelcontextprotocol/server-github"
      #     env:
      #       GITHUB_TOKEN: ${GITHUB_TOKEN}
      """;

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
      createIfAbsent(workspace.resolve("mcp_servers.yaml"), MCP_CONFIG_TEMPLATE);
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
