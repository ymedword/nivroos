package com.nivroos.storage.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.core.provider.LlmCallStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

/**
 * 审计落库验收点：颗粒度文档 §4.2（建表可写可读、可空列、非空列）。
 *
 * <p>The DDL below mirrors the llm_calls section of nivroos-boot schema.sql and must stay in sync
 * with it; the canonical script is exercised by the boot startup validation (quickstart §6).
 */
@DataJpaTest(
    properties = {
      // cache=shared：内存库跨 Hikari 连接共享（:memory: 每连接独立会丢表）
      "spring.datasource.url=jdbc:sqlite:file:llmcalls-test?mode=memory&cache=shared",
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.jpa.hibernate.ddl-auto=none"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LlmCallStoreConfiguration.class)
@Sql(
    statements =
        """
    CREATE TABLE IF NOT EXISTS llm_calls (
        id                 INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id         VARCHAR(255),
        provider           VARCHAR(64)  NOT NULL,
        model              VARCHAR(128) NOT NULL,
        prompt_tokens      INTEGER,
        completion_tokens  INTEGER,
        total_tokens       INTEGER,
        duration_ms        BIGINT       NOT NULL,
        created_at         TIMESTAMP    NOT NULL
    );
    """)
class JpaLlmCallStoreTest {

  @Autowired LlmCallStore llmCallStore;

  @Autowired LlmCallRepository repository;

  @Test
  @DisplayName("写入后可读出，字段与实际一致")
  void recordThenFind_roundTripsFields() {
    llmCallStore.record("s-1", "deepseek", "deepseek-chat", 12, 3, 15, 1234L);

    var rows = repository.findAll();
    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.getSessionId()).isEqualTo("s-1");
    assertThat(row.getProvider()).isEqualTo("deepseek");
    assertThat(row.getModel()).isEqualTo("deepseek-chat");
    assertThat(row.getPromptTokens()).isEqualTo(12);
    assertThat(row.getCompletionTokens()).isEqualTo(3);
    assertThat(row.getTotalTokens()).isEqualTo(15);
    assertThat(row.getDurationMs()).isEqualTo(1234L);
    assertThat(row.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("token 三列与 session_id 可空（失败调用/厂商未返回场景）")
  void nullableColumns_acceptNulls() {
    llmCallStore.record(null, "kimi", "moonshot-v1-8k", null, null, null, 89L);

    var row = repository.findAll().get(0);
    assertThat(row.getSessionId()).isNull();
    assertThat(row.getPromptTokens()).isNull();
    assertThat(row.getCompletionTokens()).isNull();
    assertThat(row.getTotalTokens()).isNull();
    assertThat(row.getDurationMs()).isEqualTo(89L);
  }

  @Test
  @DisplayName("多次写入逐条落库，不覆盖")
  void repeatedRecords_accumulateRows() {
    llmCallStore.record("s-1", "deepseek", "deepseek-chat", 1, 1, 2, 10L);
    llmCallStore.record("s-2", "kimi", "moonshot-v1-8k", null, null, null, 20L);

    assertThat(repository.count()).isEqualTo(2);
  }
}
