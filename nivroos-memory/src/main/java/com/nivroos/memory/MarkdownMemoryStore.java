package com.nivroos.memory;

import com.nivroos.core.memory.LongTermMemoryStore;
import com.nivroos.core.memory.MemoryScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Markdown 档长期记忆后端（默认，技术方案 §5.2）。
 *
 * <p>Backed by a single human-readable Markdown file split into two first-level sections, {@code ##
 * 核心记忆} and {@code ## 归档记忆}. Parsing is deliberately tolerant - no Markdown library, the two
 * section headers plus bullet lines are the whole grammar. Every call re-reads the file (contract
 * 1: no caching), so a write is visible to the very next load; writes take an in-process lock so
 * concurrent virtual threads cannot lose or interleave a read-modify-write cycle.
 */
public class MarkdownMemoryStore implements LongTermMemoryStore {

  private static final String FILE_HEADER = "# MEMORY.md";
  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVAL_HEADER = "## 归档记忆";
  private static final String BULLET = "- ";
  private static final String SECTION_BREAK = "\n## ";
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

  private final Path memoryFile;
  private final int archiveMaxChars;

  /** 写入互斥：读-改-写是复合操作，虚拟线程并发下必须串行化，否则后写者覆盖先写者。 */
  private final ReentrantLock writeLock = new ReentrantLock();

  /**
   * 便捷构造：归档区截断阈值取文档默认值。
   *
   * <p>Convenience constructor used when the caller has no bound configuration (tests, CLI paths);
   * production wiring goes through {@link MemoryConfiguration} and passes the configured value.
   */
  public MarkdownMemoryStore(Path memoryFile) {
    this(memoryFile, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
  }

  public MarkdownMemoryStore(Path memoryFile, int archiveMaxChars) {
    this.memoryFile = memoryFile;
    this.archiveMaxChars = archiveMaxChars;
  }

  /** 追加一条记忆：目标分区下写独立日期 header + 正文行（颗粒度文档 §2.3 伪代码）。 */
  @Override
  public void append(String content, MemoryScope scope) {
    MemoryScope target = scope == null ? MemoryScope.ARCHIVAL : scope; // 缺省 ARCHIVAL（FR-006）
    String block = "### " + DATE_FORMAT.format(LocalDate.now()) + "\n\n" + BULLET + content;

    writeLock.lock();
    try {
      String text = readFile();
      String core = sectionBody(text, CORE_HEADER);
      String archival = sectionBody(text, ARCHIVAL_HEADER);
      if (target == MemoryScope.CORE) {
        core = joinBlocks(core, block);
      } else {
        archival = joinBlocks(archival, block);
      }
      writeFile(render(core, archival));
    } finally {
      writeLock.unlock();
    }
  }

  /** 读取：核心区全量 + 归档区截断后（保留最新），核心在前、归档在后。 */
  @Override
  public String load() {
    String text = readFile();
    String core = sectionBody(text, CORE_HEADER).strip();
    String archival = sectionBody(text, ARCHIVAL_HEADER).strip();
    if (archival.length() > archiveMaxChars) {
      archival = truncateKeepingNewest(archival, archiveMaxChars);
    }
    if (core.isEmpty() && archival.isEmpty()) {
      return ""; // 空载体 = 空记忆（FR-020），不吐无内容的章节骨架
    }
    StringBuilder out = new StringBuilder();
    if (!core.isEmpty()) {
      out.append(CORE_HEADER).append("\n\n").append(core);
    }
    if (!archival.isEmpty()) {
      if (!out.isEmpty()) {
        out.append("\n\n");
      }
      out.append(ARCHIVAL_HEADER).append("\n\n").append(archival);
    }
    return out.toString();
  }

  /** 关键词行匹配，只搜归档区；返回记忆正文（不带列表符号，与 SQLite 档的 content 列同形）。 */
  @Override
  public List<String> recallByKeyword(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    List<String> hits = new ArrayList<>();
    for (String line : sectionBody(readFile(), ARCHIVAL_HEADER).split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.startsWith(BULLET) && trimmed.contains(query)) {
        hits.add(trimmed.substring(BULLET.length()).strip());
      }
    }
    return hits;
  }

  private static String joinBlocks(String body, String block) {
    return body.isBlank() ? block : body.strip() + "\n\n" + block;
  }

  /**
   * 从完整文件文本中取出指定分区的正文（不含分区 header）。
   *
   * <p>Extracts a section body by locating its first-level header and stopping at the next one; a
   * missing header yields an empty body instead of an error (tolerant parsing, FR-020).
   */
  private static String sectionBody(String text, String header) {
    int start = text.indexOf(header);
    if (start < 0) {
      return "";
    }
    int bodyStart = text.indexOf('\n', start);
    if (bodyStart < 0) {
      return "";
    }
    bodyStart++;
    int next = text.indexOf(SECTION_BREAK, bodyStart);
    return next < 0 ? text.substring(bodyStart) : text.substring(bodyStart, next);
  }

  /** 按文档骨架重排全文；空白分区只留 header，空载体与 {@code nivroos init} 的产物逐字一致。 */
  private static String render(String core, String archival) {
    StringBuilder out = new StringBuilder(FILE_HEADER).append("\n\n").append(CORE_HEADER);
    if (!core.isBlank()) {
      out.append("\n\n").append(core.strip());
    }
    out.append("\n\n").append(ARCHIVAL_HEADER);
    if (!archival.isBlank()) {
      out.append("\n\n").append(archival.strip());
    }
    return out.append("\n").toString();
  }

  /**
   * 字符串截断：从尾部（最新）往前整行保留，至少保留最新一行。
   *
   * <p>Keeps the newest whole lines whose total length fits the budget - truncation must never
   * split a line, otherwise a memory would come back half-written. The newest line is kept even
   * when it alone exceeds the budget, so a single oversized entry stays readable instead of
   * vanishing.
   */
  private static String truncateKeepingNewest(String text, int maxChars) {
    String[] lines = text.split("\n", -1);
    Deque<String> kept = new ArrayDeque<>();
    int used = 0;
    for (int i = lines.length - 1; i >= 0; i--) {
      int cost = lines[i].length() + (kept.isEmpty() ? 0 : 1);
      if (used + cost > maxChars && !kept.isEmpty()) {
        break;
      }
      kept.addFirst(lines[i]);
      used += cost;
    }
    return String.join("\n", kept);
  }

  private String readFile() {
    try {
      return Files.exists(memoryFile) ? Files.readString(memoryFile) : "";
    } catch (IOException e) {
      // 不做「无记忆」降级：降级决策归 MemoryService（读取失败记 WARN 后继续），store 只负责报错
      throw new UncheckedIOException("Failed to read memory file: " + memoryFile, e);
    }
  }

  private void writeFile(String text) {
    try {
      Path parent = memoryFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(memoryFile, text);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write memory file: " + memoryFile, e);
    }
  }
}
