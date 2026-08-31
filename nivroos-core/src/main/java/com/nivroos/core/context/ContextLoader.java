package com.nivroos.core.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文加载（技术方案 §8.3 简化版；Skill 软连接扫描归 US-4）。
 *
 * <p>Loads the AGENT.md body plus the three Bootstrap files and appends the current date-time. No
 * caching: every call re-reads the files, so edits take effect on the next round (FR-010). Missing
 * Bootstrap files warn but never block.
 */
public class ContextLoader {

  private static final Logger log = LoggerFactory.getLogger(ContextLoader.class);

  private static final String[] BOOTSTRAP_FILES = {"AGENTS.md", "SOUL.md", "USER.md"};

  private final Path agentDir;
  private final Path workspaceRoot;

  public ContextLoader(Path agentDir, Path workspaceRoot) {
    this.agentDir = agentDir;
    this.workspaceRoot = workspaceRoot;
  }

  /** 组装系统提示词：AGENT.md 正文 + Bootstrap 三文件 + 末尾当前日期时间。 */
  public String loadSystemPrompt() {
    StringBuilder prompt = new StringBuilder();

    String body = readAgentBody();
    if (body.isBlank()) {
      log.warn("AGENT.md body is blank: {}", sanitizeForLog(agentDir.toString()));
    }
    prompt.append(body).append('\n');

    for (String fileName : BOOTSTRAP_FILES) {
      Path file = workspaceRoot.resolve(fileName);
      try {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        prompt.append("\n## ").append(fileName).append("\n").append(content).append('\n');
      } catch (IOException e) {
        // Bootstrap 缺失告警不阻断（FR-010）
        log.warn("Bootstrap file missing, skipped: {}", sanitizeForLog(file.toString()));
      }
    }

    // 末尾附当前日期时间——模型不知道今天几号，定时场景的"今天"全靠这一行
    prompt
        .append("\n当前日期时间: ")
        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .append('\n');
    return prompt.toString();
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
