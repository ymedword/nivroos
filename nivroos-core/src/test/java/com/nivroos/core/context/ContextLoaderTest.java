package com.nivroos.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nivroos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import org.slf4j.LoggerFactory;

/** 上下文加载验收点：颗粒度文档 §4.2（Bootstrap 按 Profile 生效 / Skill L1 / 无缓存）。 */
class ContextLoaderTest {

  @TempDir Path workspaceRoot;

  @Test
  @DisplayName("正文 + Bootstrap 拼接，末尾含当前日期时间")
  void loadSystemPrompt_assemblesBodyBootstrapAndDate() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n你是天气助手。");
    writeBootstrap("AGENTS.md", "项目级行为说明");
    writeBootstrap("SOUL.md", "人格定义");
    writeBootstrap("USER.md", "用户偏好");

    String prompt =
        loader(profileWithBootstrap("AGENTS.md", "SOUL.md", "USER.md")).loadSystemPrompt();

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
    writeBootstrap("AGENTS.md", "v1");
    ContextLoader loader = loader(profileWithBootstrap("AGENTS.md"));

    assertThat(loader.loadSystemPrompt()).contains("第一版指令");

    writeAgent("weather", "---\nname: weather\n---\n第二版指令");
    writeBootstrap("AGENTS.md", "v2");

    assertThat(loader.loadSystemPrompt()).contains("第二版指令");
    assertThat(loader.loadSystemPrompt()).contains("v2");
  }

  @Test
  @DisplayName("Bootstrap 按 Profile.bootstrap 生效：声明的读、没声明的不读")
  void loadSystemPrompt_usesDeclaredBootstrapOnly() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");
    writeBootstrap("OPS.md", "运维约定");
    writeBootstrap("AGENTS.md", "项目级行为说明");

    String prompt = loader(profileWithBootstrap("OPS.md")).loadSystemPrompt();

    assertThat(prompt).contains("运维约定");
    assertThat(prompt).doesNotContain("项目级行为说明");
  }

  @Test
  @DisplayName("Bootstrap 字段缺失 → 回退默认三件（AGENTS.md / SOUL.md / USER.md）并 WARN")
  void loadSystemPrompt_bootstrapMissing_fallsBackToDefaultsWithWarning() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");
    writeBootstrap("AGENTS.md", "项目级行为说明");
    writeBootstrap("SOUL.md", "人格定义");
    writeBootstrap("USER.md", "用户偏好");

    String prompt;
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      prompt = loader(new Profile()).loadSystemPrompt();
    } finally {
      detachAppender(appender);
    }

    assertThat(prompt).contains("项目级行为说明").contains("人格定义").contains("用户偏好");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("bootstrap not declared");
            });
  }

  @Test
  @DisplayName("Bootstrap 声明了但文件缺失 → WARN 不阻断")
  void loadSystemPrompt_declaredBootstrapMissing_warnsButContinues() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");

    String prompt;
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      prompt = loader(profileWithBootstrap("AGENTS.md")).loadSystemPrompt();
    } finally {
      detachAppender(appender);
    }

    assertThat(prompt).contains("正文");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("AGENTS.md");
            });
  }

  @Test
  @DisplayName("验收点：Skill L1 只注入 name + description + 本地绝对路径，不含 SKILL.md 正文")
  void loadSystemPrompt_skillBinding_injectsMetadataOnly() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");
    writePublicSkill("digest-format", "科技日报组稿格式", "先查源，再组稿，最后排版。");
    bindSkill("digest-format", "digest-format");

    String prompt = loader(new Profile()).loadSystemPrompt();

    assertThat(prompt).contains("## 可用技能");
    assertThat(prompt).contains("digest-format");
    assertThat(prompt).contains("科技日报组稿格式");
    assertThat(prompt)
        .contains(agentSkillsDir().resolve("digest-format").toAbsolutePath() + "/SKILL.md");
    // 渐进披露：正文只能经 read_file 现取（L2），不得预载进 system prompt
    assertThat(prompt).doesNotContain("先查源，再组稿，最后排版。");
  }

  @Test
  @DisplayName("软连接断链 → WARN 跳过，不阻断对话")
  void loadSystemPrompt_brokenSkillBinding_skippedWithWarning() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");
    bindDanglingSkill("gone-skill");

    String prompt;
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      prompt = loader(new Profile()).loadSystemPrompt();
    } finally {
      detachAppender(appender);
    }

    assertThat(prompt).contains("正文").doesNotContain("## 可用技能");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("broken");
            });
  }

  @Test
  @DisplayName("SKILL.md 缺 name / description → WARN 跳过，不阻断对话")
  void loadSystemPrompt_skillWithoutFrontmatter_skippedWithWarning() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");
    writePublicSkill("no-meta", null, "有正文但缺 frontmatter。");
    bindSkill("no-meta", "no-meta");

    String prompt;
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      prompt = loader(new Profile()).loadSystemPrompt();
    } finally {
      detachAppender(appender);
    }

    assertThat(prompt).contains("正文").doesNotContain("## 可用技能");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("name and description");
            });
  }

  @Test
  @DisplayName("绑定真实目标越出公共技能库 → WARN 跳过、不注入（FR-028）")
  void loadSystemPrompt_bindingTargetOutsidePublicRoot_skippedWithWarning() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");
    // 库外技能实体：真实目标在 `.nivroos/skills/` 之外，越界绑定不得进 prompt
    Path outside = workspaceRoot.resolve("elsewhere/leak");
    Files.createDirectories(outside);
    Files.writeString(
        outside.resolve("SKILL.md"),
        "---\nname: leak\ndescription: 库外技能\n---\n库外正文",
        StandardCharsets.UTF_8);
    Path binding = agentSkillsDir().resolve("leak");
    Files.createDirectories(agentSkillsDir());
    try {
      Files.createSymbolicLink(binding, outside);
    } catch (IOException | UnsupportedOperationException e) {
      throw new TestAbortedException("platform cannot create symlinks: " + e.getMessage());
    }

    String prompt;
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      prompt = loader(new Profile()).loadSystemPrompt();
    } finally {
      detachAppender(appender);
    }

    assertThat(prompt).contains("正文").doesNotContain("## 可用技能").doesNotContain("leak");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("outside the public skill root");
            });
  }

  @Test
  @DisplayName("验收点：每轮重扫——新增技能下一轮可见、移除即消失")
  void loadSystemPrompt_rescansSkillBindingsEveryCall() throws IOException {
    writeAgent("weather", "---\nname: weather\n---\n正文");
    ContextLoader loader = loader(new Profile());

    assertThat(loader.loadSystemPrompt()).doesNotContain("## 可用技能");

    writePublicSkill("digest-format", "科技日报组稿格式", "正文第一步。");
    bindSkill("digest-format", "digest-format");
    assertThat(loader.loadSystemPrompt()).contains("digest-format");

    Files.delete(agentSkillsDir().resolve("digest-format"));
    assertThat(loader.loadSystemPrompt()).doesNotContain("## 可用技能");
  }

  // ------------------------------------------------ 测试内助手

  private ContextLoader loader(Profile profile) {
    return new ContextLoader(profile, workspaceRoot.resolve("agents/weather"), workspaceRoot);
  }

  private static Profile profileWithBootstrap(String... bootstrap) {
    Profile profile = new Profile();
    profile.setName("weather");
    profile.setBootstrap(List.of(bootstrap));
    return profile;
  }

  private Path agentSkillsDir() {
    return workspaceRoot.resolve("agents/weather/skills");
  }

  private void writeAgent(String name, String content) throws IOException {
    Path dir = workspaceRoot.resolve("agents").resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
  }

  private void writeBootstrap(String fileName, String content) throws IOException {
    Files.writeString(workspaceRoot.resolve(fileName), content, StandardCharsets.UTF_8);
  }

  /** 公共技能实体：`.nivroos/skills/<name>/SKILL.md`（frontmatter 为 null 时写成无 frontmatter 的正文）。 */
  private void writePublicSkill(String name, String title, String body) throws IOException {
    Path dir = workspaceRoot.resolve("skills").resolve(name);
    Files.createDirectories(dir);
    String content =
        title == null ? body : "---\nname: " + name + "\ndescription: " + title + "\n---\n" + body;
    Files.writeString(dir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
  }

  /** Agent 本地相对软连接绑定（`.nivroos/agents/<agent>/skills/<name>` → ../../../skills/<name>）。 */
  private void bindSkill(String bindingName, String skillName) throws IOException {
    Files.createDirectories(agentSkillsDir());
    try {
      Files.createSymbolicLink(
          agentSkillsDir().resolve(bindingName), Path.of("../../../skills", skillName));
    } catch (IOException | UnsupportedOperationException e) {
      throw new TestAbortedException("platform cannot create symlinks: " + e.getMessage());
    }
  }

  private void bindDanglingSkill(String bindingName) throws IOException {
    Files.createDirectories(agentSkillsDir());
    try {
      Files.createSymbolicLink(
          agentSkillsDir().resolve(bindingName), Path.of("../../../skills/never-created"));
    } catch (IOException | UnsupportedOperationException e) {
      throw new TestAbortedException("platform cannot create symlinks: " + e.getMessage());
    }
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ContextLoader.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(ContextLoader.class)).detachAppender(appender);
  }
}
