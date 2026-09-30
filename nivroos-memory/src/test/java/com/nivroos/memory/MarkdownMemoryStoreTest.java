package com.nivroos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.core.memory.MemoryScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MarkdownMemoryStore 验收（颗粒度文档 §4.2 / §4.3）。
 *
 * <p>Covers the two key regressions verbatim plus the four behavioural contracts: no caching, CORE
 * never truncated, scope given by the caller, keyword recall over ARCHIVAL only. Uses @TempDir so
 * the real {@code .nivroos/memory/MEMORY.md} is never touched.
 */
class MarkdownMemoryStoreTest {

  @TempDir Path tempDir;

  /** 已存在的空载体（FR-020 的「存在但内容为空」分支）；可重复调用，不覆盖已有内容。 */
  private Path memoryFile() throws IOException {
    Path file = tempDir.resolve("MEMORY.md");
    if (Files.notExists(file)) {
      Files.createFile(file);
    }
    return file;
  }

  /** 不存在的载体（FR-020 的「首次运行」分支）。 */
  private Path missingMemoryFile() {
    return tempDir.resolve("MEMORY.md");
  }

  // ---------------------------------------------------------------- 关键回归（§4.3 原样落地）

  @Test
  @DisplayName("核心记忆区永不被截断：归档区超限后核心区完整返回")
  void load_coreSectionNeverTruncated() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());
    store.append("核心偏好：使用 Spring Boot", MemoryScope.CORE);
    for (int i = 0; i < 200; i++) {
      store.append("归档条目 " + i, MemoryScope.ARCHIVAL); // 塞爆归档区
    }

    String loaded = store.load();

    assertThat(loaded).contains("核心偏好：使用 Spring Boot"); // 核心区完整
    assertThat(loaded.split("归档条目").length - 1).isLessThan(200); // 归档区被截断
  }

  @Test
  @DisplayName("不缓存：save 后下一次 load 立即读到新内容")
  void load_rereadsFileAfterAppend() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());
    store.append("第一版偏好", MemoryScope.CORE);

    assertThat(store.load()).contains("第一版偏好");

    store.append("第二版偏好", MemoryScope.CORE);

    assertThat(store.load()).contains("第二版偏好"); // 无缓存，重读生效
  }

  // ---------------------------------------------------------------- 分区与默认值

  @Test
  @DisplayName("append 按 scope 进对应分区：CORE 进核心区、ARCHIVAL 进归档区")
  void append_writesToSectionByScope() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());
    store.append("核心内容", MemoryScope.CORE);
    store.append("归档内容", MemoryScope.ARCHIVAL);

    String text = Files.readString(memoryFile());

    assertThat(text).contains("## 核心记忆").contains("核心内容");
    assertThat(text).contains("## 归档记忆").contains("归档内容");
    int coreIdx = text.indexOf("核心内容");
    int archivalHeaderIdx = text.indexOf("## 归档记忆");
    assertThat(coreIdx).isLessThan(archivalHeaderIdx); // 核心区在归档区之前
  }

  @Test
  @DisplayName("scope 缺省 ARCHIVAL：不指定分区时写入归档区")
  void append_nullScope_defaultsToArchival() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());

    store.append("默认分区内容", null);

    String text = Files.readString(memoryFile());
    assertThat(text.indexOf("默认分区内容")).isGreaterThan(text.indexOf("## 归档记忆"));
  }

  @Test
  @DisplayName("归档区截断保留最新：最旧的条目先被丢弃")
  void load_archivalTruncation_keepsNewest() {
    MarkdownMemoryStore store = new MarkdownMemoryStore(missingMemoryFile(), 120);
    for (int i = 0; i < 20; i++) {
      store.append("归档条目 " + i, MemoryScope.ARCHIVAL);
    }

    String loaded = store.load();

    assertThat(loaded).contains("归档条目 19"); // 最新保留
    assertThat(loaded).doesNotContain("归档条目 0"); // 最旧丢弃
  }

  // ---------------------------------------------------------------- 契约④：关键词检索（只搜归档区）

  @Test
  @DisplayName("recallByKeyword 只搜归档区：命中归档区内容")
  void recallByKeyword_matchesArchival() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());
    store.append("上次讨论过使用 SQLite 作为本地存储", MemoryScope.ARCHIVAL);
    store.append("另有一条无关记忆", MemoryScope.ARCHIVAL);

    List<String> hits = store.recallByKeyword("SQLite");

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0)).contains("SQLite");
  }

  @Test
  @DisplayName("recallByKeyword 不返回核心区内容：核心区不参与检索")
  void recallByKeyword_coreSectionNotSearched() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());
    store.append("项目使用 Spring Boot", MemoryScope.CORE);

    assertThat(store.recallByKeyword("Spring Boot")).isEmpty();
  }

  @Test
  @DisplayName("recallByKeyword 无命中返回空列表")
  void recallByKeyword_noMatch_returnsEmptyList() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());
    store.append("一条记忆", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("不存在的关键词")).isEmpty();
  }

  // ---------------------------------------------------------------- 容错（FR-020）

  @Test
  @DisplayName("载体不存在视为空记忆：不报错、正常追加")
  void load_missingFile_treatedAsEmptyMemory() {
    MarkdownMemoryStore store = new MarkdownMemoryStore(missingMemoryFile());

    assertThat(store.load()).isEmpty();

    store.append("首次写入", MemoryScope.CORE);

    assertThat(store.load()).contains("首次写入");
  }

  @Test
  @DisplayName("载体为空文件视为空记忆：不报错")
  void load_emptyFile_treatedAsEmptyMemory() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());

    assertThat(store.load()).isEmpty();
  }

  @Test
  @DisplayName("分区 header 缺失时按需补建")
  void append_createsMissingSectionHeader() throws IOException {
    Path file = memoryFile();
    Files.writeString(file, "# MEMORY.md\n");

    MarkdownMemoryStore store = new MarkdownMemoryStore(file);
    store.append("补建后的内容", MemoryScope.CORE);

    String text = Files.readString(file);
    assertThat(text).contains("## 核心记忆").contains("## 归档记忆").contains("补建后的内容");
  }

  @Test
  @DisplayName("写入带日期 header：条目落在 ### <yyyy-MM-dd> 之下")
  void append_writesDateHeader() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());

    store.append("带日期的记忆", MemoryScope.ARCHIVAL);

    String text = Files.readString(memoryFile());
    assertThat(text).containsPattern("### \\d{4}-\\d{2}-\\d{2}");
    assertThat(text).contains("- 带日期的记忆");
  }
}
