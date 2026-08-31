package com.nivroos.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 上下文加载验收点：颗粒度文档 §4.2（FR-005 / FR-010 无缓存）。 */
class ContextLoaderTest {

  @TempDir Path workspaceRoot;

  @Test
  @DisplayName("正文 + Bootstrap 拼接，末尾含当前日期时间")
  void loadSystemPrompt_assemblesBodyBootstrapAndDate() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n你是天气助手。");
    Files.writeString(workspaceRoot.resolve("AGENTS.md"), "项目级行为说明", StandardCharsets.UTF_8);
    Files.writeString(workspaceRoot.resolve("SOUL.md"), "人格定义", StandardCharsets.UTF_8);
    Files.writeString(workspaceRoot.resolve("USER.md"), "用户偏好", StandardCharsets.UTF_8);

    ContextLoader loader =
        new ContextLoader(workspaceRoot.resolve("agents/weather"), workspaceRoot);

    String prompt = loader.loadSystemPrompt();

    assertThat(prompt).contains("你是天气助手。");
    assertThat(prompt).contains("项目级行为说明");
    assertThat(prompt).contains("人格定义");
    assertThat(prompt).contains("用户偏好");
    assertThat(prompt).contains("当前日期时间");
  }

  @Test
  @DisplayName("验收点：无缓存——改文件后下一次 build 立即读到新内容")
  void loadSystemPrompt_rereadsFileAfterChange() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n第一版指令");
    Files.writeString(workspaceRoot.resolve("AGENTS.md"), "v1", StandardCharsets.UTF_8);
    ContextLoader loader =
        new ContextLoader(workspaceRoot.resolve("agents/weather"), workspaceRoot);

    assertThat(loader.loadSystemPrompt()).contains("第一版指令");

    writeAgent("weather", "---\nname: weather\n---\n第二版指令");
    Files.writeString(workspaceRoot.resolve("AGENTS.md"), "v2", StandardCharsets.UTF_8);

    assertThat(loader.loadSystemPrompt()).contains("第二版指令");
    assertThat(loader.loadSystemPrompt()).contains("v2");
  }

  @Test
  @DisplayName("Bootstrap 缺失时告警不阻断")
  void loadSystemPrompt_missingBootstrap_warnsButContinues() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");

    ContextLoader loader =
        new ContextLoader(workspaceRoot.resolve("agents/weather"), workspaceRoot);

    String prompt = loader.loadSystemPrompt();

    assertThat(prompt).contains("正文");
  }

  private void writeAgent(String name, String content) throws IOException {
    Path dir = workspaceRoot.resolve("agents").resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
  }
}
