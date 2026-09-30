package com.nivroos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.core.memory.MemoryScope;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

/**
 * SqliteMemoryStore 验收点：颗粒度文档 §4.2（建表可写可读、scope 过滤、LIKE 检索、归档截断）。
 *
 * <p>The DDL below mirrors the memory_entries section of nivroos-boot schema.sql and must stay in
 * sync with it. In-memory shared-cache database, never the real {@code .nivroos/nivroos.db}: an
 * open keeper connection holds the database alive across JdbcTemplate connections (a {@code
 * :memory:} database is per-connection and would lose its table).
 */
class SqliteMemoryStoreTest {

  private SQLiteDataSource dataSource;
  private Connection keeper;

  @BeforeEach
  void setUp() throws SQLException {
    dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:file:memory-" + UUID.randomUUID() + "?mode=memory&cache=shared");
    keeper = dataSource.getConnection();
    try (Statement statement = keeper.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS memory_entries (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              scope      VARCHAR(16) NOT NULL,
              content    TEXT        NOT NULL,
              created_at TIMESTAMP   NOT NULL
          );
          """);
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    keeper.close(); // 最后一个连接关闭，内存库随之回收
  }

  private SqliteMemoryStore store(int archiveMaxChars) {
    return new SqliteMemoryStore(dataSource, archiveMaxChars);
  }

  // ---------------------------------------------------------------- 基本读写与分区

  @Test
  @DisplayName("空表 = 空记忆：load 返回空串")
  void load_emptyTable_returnsEmpty() {
    assertThat(store(MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS).load()).isEmpty();
  }

  @Test
  @DisplayName("append 按 scope 落库，load 核心区在前、归档区在后")
  void append_scopeFiltering_loadPutsCoreFirst() {
    SqliteMemoryStore store = store(MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    store.append("核心偏好：Spring Boot", MemoryScope.CORE);
    store.append("上次讨论过 SQLite", MemoryScope.ARCHIVAL);

    String loaded = store.load();

    assertThat(loaded).contains("核心偏好：Spring Boot").contains("上次讨论过 SQLite");
    assertThat(loaded.indexOf("核心偏好：Spring Boot")).isLessThan(loaded.indexOf("上次讨论过 SQLite"));
  }

  @Test
  @DisplayName("scope 缺省 ARCHIVAL：不指定分区时落归档区")
  void append_nullScope_defaultsToArchival() {
    SqliteMemoryStore store = store(MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    store.append("默认分区内容", null);

    String loaded = store.load();
    assertThat(loaded).contains("默认分区内容");
    assertThat(loaded.substring(0, loaded.indexOf("默认分区内容"))).contains("归档");
  }

  // ---------------------------------------------------------------- 关键回归：核心区永不被截断

  @Test
  @DisplayName("核心记忆区永不被截断：归档区超限后核心区完整返回")
  void load_coreSectionNeverTruncated() {
    SqliteMemoryStore store = store(200); // 小预算，逼出归档区截断
    store.append("核心偏好：使用 Spring Boot", MemoryScope.CORE);
    for (int i = 0; i < 200; i++) {
      store.append("归档条目 " + i, MemoryScope.ARCHIVAL); // 塞爆归档区
    }

    String loaded = store.load();

    assertThat(loaded).contains("核心偏好：使用 Spring Boot"); // 核心区完整
    assertThat(loaded.split("归档条目").length - 1).isLessThan(200); // 归档区被截断
  }

  @Test
  @DisplayName("归档区截断保留最新：最旧的条目先被丢弃")
  void load_archivalTruncation_keepsNewest() {
    SqliteMemoryStore store = store(60);
    for (int i = 0; i < 20; i++) {
      store.append("归档条目 " + i, MemoryScope.ARCHIVAL);
    }

    String loaded = store.load();

    assertThat(loaded).contains("归档条目 19");
    assertThat(loaded).doesNotContain("归档条目 0");
  }

  // ---------------------------------------------------------------- 契约④：关键词检索（只搜归档区）

  @Test
  @DisplayName("recallByKeyword 只搜归档区：命中归档区内容")
  void recallByKeyword_matchesArchival() {
    SqliteMemoryStore store = store(MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    store.append("上次讨论过使用 SQLite 作为本地存储", MemoryScope.ARCHIVAL);
    store.append("另有一条无关记忆", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("SQLite")).containsExactly("上次讨论过使用 SQLite 作为本地存储");
  }

  @Test
  @DisplayName("recallByKeyword 不返回核心区内容（FR-009）")
  void recallByKeyword_coreSectionNotSearched() {
    SqliteMemoryStore store = store(MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    store.append("项目使用 Spring Boot", MemoryScope.CORE);

    assertThat(store.recallByKeyword("Spring Boot")).isEmpty();
  }

  @Test
  @DisplayName("recallByKeyword 无命中返回空列表")
  void recallByKeyword_noMatch_returnsEmptyList() {
    SqliteMemoryStore store = store(MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    store.append("一条记忆", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("不存在的关键词")).isEmpty();
  }

  @Test
  @DisplayName("LIKE 通配符按字面处理：% 与 _ 不放大匹配范围")
  void recallByKeyword_wildcardsAreEscaped() {
    SqliteMemoryStore store = store(MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    store.append("完成度 100% 的记录", MemoryScope.ARCHIVAL);
    store.append("普通记录", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("%")).containsExactly("完成度 100% 的记录");
    assertThat(store.recallByKeyword("_")).isEmpty();
  }
}
