package com.nivroos.memory;

import com.nivroos.core.memory.LongTermMemoryStore;
import com.nivroos.core.memory.MemoryScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQLite 档长期记忆后端（`memory.backend: sqlite`，技术方案 §5.1）。
 *
 * <p>Same two-section semantics as the Markdown backend, carried by the {@code memory_entries}
 * table instead of a file. Plain {@link JdbcTemplate} - no JPA entity and no repository, because
 * the table is maintained by the hand-written {@code schema.sql} and nothing else needs an object
 * mapping. Every call hits the database (contract 1: no caching).
 */
public class SqliteMemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVAL_HEADER = "## 归档记忆";
  private static final String BULLET = "- ";

  /** LIKE 转义字符：用户关键词里的 % 与 _ 必须按字面匹配，否则一个 % 会召回整表。 */
  private static final char LIKE_ESCAPE = '\\';

  private final JdbcTemplate jdbc;
  private final int archiveMaxChars;

  public SqliteMemoryStore(DataSource dataSource, int archiveMaxChars) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.archiveMaxChars = archiveMaxChars;
  }

  /** 追加一条记忆：核心区全量保留，归档区由 {@link #load()} 按字符预算截断。 */
  @Override
  public void append(String content, MemoryScope scope) {
    MemoryScope target = scope == null ? MemoryScope.ARCHIVAL : scope; // 缺省 ARCHIVAL（FR-006）
    jdbc.update(
        "INSERT INTO memory_entries(scope, content, created_at) VALUES (?,?,?)",
        target.name(),
        content,
        Timestamp.from(Instant.now()));
  }

  /** 读取：核心区全量 + 归档区按字符预算保留最新，核心在前、归档在后。 */
  @Override
  public String load() {
    return render(coreEntries(), archivalWithinBudget());
  }

  /** 关键词行匹配，只搜归档区；返回记忆正文（与 markdown 档同形，不带列表符号）。 */
  @Override
  public List<String> recallByKeyword(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    return jdbc.queryForList(
        "SELECT content FROM memory_entries WHERE scope = ? AND content LIKE ? ESCAPE '"
            + LIKE_ESCAPE
            + "' ORDER BY id",
        String.class,
        MemoryScope.ARCHIVAL.name(),
        "%" + escapeLike(query) + "%");
  }

  private List<String> coreEntries() {
    return jdbc.queryForList(
        "SELECT content FROM memory_entries WHERE scope = ? ORDER BY id",
        String.class,
        MemoryScope.CORE.name());
  }

  /**
   * 归档区取最新若干条：倒序取到字符预算耗尽为止，再恢复时间正序。
   *
   * <p>Budget in characters rather than rows - "how much of the archive fits in the prompt" is the
   * real constraint, and it must mean the same thing here as in the file backend. The newest entry
   * is always kept, even when it alone exceeds the budget, so an oversized memory never vanishes.
   */
  private List<String> archivalWithinBudget() {
    List<String> rows =
        jdbc.queryForList(
            "SELECT content FROM memory_entries WHERE scope = ? ORDER BY id DESC",
            String.class,
            MemoryScope.ARCHIVAL.name());
    List<String> kept = new ArrayList<>();
    int used = 0;
    for (String row : rows) {
      int cost = row.length() + BULLET.length();
      if (used + cost > archiveMaxChars && !kept.isEmpty()) {
        break;
      }
      kept.add(row);
      used += cost;
    }
    Collections.reverse(kept); // 倒序取、正序出
    return kept;
  }

  private static String render(List<String> core, List<String> archival) {
    if (core.isEmpty() && archival.isEmpty()) {
      return ""; // 空表 = 空记忆（FR-020 同语义）
    }
    StringBuilder out = new StringBuilder();
    appendSection(out, CORE_HEADER, core);
    appendSection(out, ARCHIVAL_HEADER, archival);
    return out.toString();
  }

  private static void appendSection(StringBuilder out, String header, List<String> entries) {
    if (entries.isEmpty()) {
      return;
    }
    if (!out.isEmpty()) {
      out.append("\n\n");
    }
    out.append(header);
    for (String entry : entries) {
      out.append("\n\n").append(BULLET).append(entry);
    }
  }

  private static String escapeLike(String query) {
    StringBuilder escaped = new StringBuilder(query.length());
    for (char c : query.toCharArray()) {
      if (c == LIKE_ESCAPE || c == '%' || c == '_') {
        escaped.append(LIKE_ESCAPE);
      }
      escaped.append(c);
    }
    return escaped.toString();
  }
}
