package com.nivroos.core.context;

import com.nivroos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * 上下文加载（技术方案 §8.3）。
 *
 * <p>Loads the AGENT.md body, the Bootstrap files declared by {@link Profile#getBootstrap()} and
 * the L1 skill metadata, then appends the current date-time. No caching：每次调用都重读文件、重扫技能软
 * 连接，改动与重新绑定下一轮立即生效（FR-010）。
 *
 * <p>Skill 渐进披露只做 L1：注入 {@code name} + {@code description} + Agent 本地绝对路径，正文留给 模型经 {@code
 * read_file} 现取（L2，宪法原则四）。
 */
public class ContextLoader {

  private static final Logger log = LoggerFactory.getLogger(ContextLoader.class);

  private static final Yaml YAML = new Yaml();

  /** Profile 未声明 {@code bootstrap} 时的回退列表（技术方案 §8.2）。 */
  private static final List<String> DEFAULT_BOOTSTRAP_FILES =
      List.of("AGENTS.md", "SOUL.md", "USER.md");

  private static final String SKILL_FILE = "SKILL.md";

  /** 公共技能库相对工作区的路径（技术方案 §8.1）。 */
  private static final String PUBLIC_SKILLS_DIR = "skills";

  /**
   * 引导文件列表——构造时从 {@code Profile.bootstrap} 取定（Profile 本身是启动期快照，工具池、provider
   * 也都是启动期读物），文件**内容**仍每轮重读。
   */
  private final List<String> bootstrapFiles;

  private final Path agentDir;
  private final Path workspaceRoot;

  public ContextLoader(Profile profile, Path agentDir, Path workspaceRoot) {
    this.agentDir = agentDir;
    this.workspaceRoot = workspaceRoot;
    List<String> declared = profile.getBootstrap();
    if (declared.isEmpty()) {
      // 未声明是"没写"而不是明确不要，所以回退默认三件——WARN 只在启动装配时打一次，不刷每轮
      log.warn(
          "bootstrap not declared in AGENT.md, falling back to the default three files: profile={}",
          sanitizeForLog(profile.getName()));
      this.bootstrapFiles = DEFAULT_BOOTSTRAP_FILES;
    } else {
      this.bootstrapFiles = List.copyOf(declared);
    }
  }

  /** 组装系统提示词：AGENT.md 正文 + Bootstrap + 可用技能（L1）+ 末尾当前日期时间。 */
  public String loadSystemPrompt() {
    StringBuilder prompt = new StringBuilder();

    String body = readAgentBody();
    if (body.isBlank()) {
      log.warn("AGENT.md body is blank: {}", sanitizeForLog(agentDir.toString()));
    }
    prompt.append(body).append('\n');

    appendBootstrap(prompt);
    appendSkills(prompt);

    // 末尾附当前日期时间——模型不知道今天几号，定时场景的"今天"全靠这一行
    prompt
        .append("\n当前日期时间: ")
        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .append('\n');
    return prompt.toString();
  }

  /**
   * 引导文件按 Agent 声明逐个注入；文件缺失告警不阻断（FR-008 / FR-010）。
   *
   * <p>声明为空时构造器已回退默认三件，这里只管读——回退的 WARN 在构造器、缺失的 WARN 在这里，日志能区分。
   */
  private void appendBootstrap(StringBuilder prompt) {
    for (String fileName : bootstrapFiles) {
      Path file = workspaceRoot.resolve(fileName);
      try {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        prompt.append("\n## ").append(fileName).append("\n").append(content).append('\n');
      } catch (IOException e) {
        // Bootstrap 缺失告警不阻断（FR-010）
        log.warn("Bootstrap file missing, skipped: {}", sanitizeForLog(file.toString()));
      }
    }
  }

  /**
   * Skill 元数据（L1）注入——每轮重扫 Agent 本地 {@code skills/} 软连接（宪法原则四）。
   *
   * <p>Only name, description and the agent-local absolute path reach the prompt; the SKILL.md body
   * is never preloaded. 绑定真相源是软连接本身：断链、越出公共技能库（FR-028）、缺必需字段一律 WARN 跳过，不阻断对话。
   */
  private void appendSkills(StringBuilder prompt) {
    Path bindingsDir = agentDir.resolve("skills");
    if (!Files.isDirectory(bindingsDir)) {
      return; // 光杆 Agent 没有 skills/ 绑定目录是常态，不是异常
    }
    // 公共技能根可能尚不存在：逐条绑定各给各的跳过原因，不整段静默
    Path publicSkillsRoot = realPathOrNull(workspaceRoot.resolve(PUBLIC_SKILLS_DIR));
    List<String> lines = new ArrayList<>();
    try (Stream<Path> entries = Files.list(bindingsDir)) {
      for (Path binding : entries.filter(Files::isSymbolicLink).sorted().toList()) {
        String line = describeSkill(binding, publicSkillsRoot);
        if (line != null) {
          lines.add(line);
        }
      }
    } catch (IOException e) {
      log.warn(
          "skill bindings unreadable, skill section omitted: {}",
          sanitizeForLog(bindingsDir.toString()));
      return;
    }

    if (!lines.isEmpty()) {
      prompt.append("\n## 可用技能\n");
      lines.forEach(line -> prompt.append(line).append('\n'));
    }
  }

  /**
   * 单个绑定 → 一行 L1 元数据；任一校验不过返回 null（调用方只做收集）。
   *
   * <p>Paths are compared after {@code toRealPath()} on both sides, so a link that merely spells
   * its way inside the root is accepted while an outward one is not（FR-028 的越界判定）。
   */
  private static String describeSkill(Path binding, Path publicSkillsRoot) {
    Path target = realPathOrNull(binding);
    if (target == null) {
      log.warn("skill binding is broken, skipped: {}", sanitizeForLog(binding.toString()));
      return null;
    }
    if (publicSkillsRoot == null || !target.startsWith(publicSkillsRoot)) {
      log.warn(
          "skill binding points outside the public skill root, skipped: binding={}, target={}, root={}",
          sanitizeForLog(binding.toString()),
          sanitizeForLog(target.toString()),
          sanitizeForLog(String.valueOf(publicSkillsRoot)));
      return null;
    }

    Map<String, Object> frontmatter;
    try {
      frontmatter = parseSkillFrontmatter(Files.readString(target.resolve(SKILL_FILE)));
    } catch (IOException e) {
      log.warn("SKILL.md unreadable, skill skipped: {}", sanitizeForLog(target.toString()));
      return null;
    }
    String name = text(frontmatter.get("name"));
    String description = text(frontmatter.get("description"));
    if (name == null || name.isBlank() || description == null || description.isBlank()) {
      log.warn(
          "SKILL.md frontmatter must carry name and description, skill skipped: {}",
          sanitizeForLog(target.toString()));
      return null;
    }
    // 路径取 Agent 本地绝对路径（穿过软连接）——模型据此 read_file 取正文，与 Demo 二一致
    return "- "
        + name
        + ": "
        + description
        + "（正文: "
        + binding.toAbsolutePath()
        + "/"
        + SKILL_FILE
        + "）";
  }

  /** 读 AGENT.md 并剥离 frontmatter，返回正文部分（无缓存，每次重读）。 */
  private String readAgentBody() {
    Path agentFile = agentDir.resolve("AGENT.md");
    try {
      String raw = Files.readString(agentFile, StandardCharsets.UTF_8);
      return stripFrontmatter(raw);
    } catch (IOException e) {
      log.warn("AGENT.md unreadable, body omitted: {}", sanitizeForLog(agentFile.toString()));
      return "";
    }
  }

  /** SKILL.md 的 frontmatter；没有 frontmatter 或不是映射时返回空映射（由调用方按缺字段告警）。 */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseSkillFrontmatter(String raw) {
    String text = raw.strip();
    if (stripFrontmatter(text).equals(text)) {
      return Map.of();
    }
    Object loaded = YAML.load(text.substring(3, text.indexOf("---", 3)).strip());
    return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  /**
   * 软连接目标解析；断链 / 不存在返回 null（调用方告警跳过）。
   *
   * <p>Both the target and the public root go through {@code toRealPath()} so a symlinked workspace
   * (macOS {@code /tmp} → {@code /private/tmp}) cannot make an in-root binding look outward.
   */
  private static Path realPathOrNull(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }

  private static String text(Object raw) {
    return raw == null ? null : String.valueOf(raw);
  }

  /** 日志参数 CRLF 消毒：路径来自本地配置，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }

  /** frontmatter 以 --- 包裹，正文取第二个 --- 之后的部分。 */
  public static String stripFrontmatter(String raw) {
    String text = raw.strip();
    if (!text.startsWith("---")) {
      return text;
    }
    int end = text.indexOf("---", 3);
    return end < 0 ? "" : text.substring(end + 3).strip();
  }
}
