package com.nivroos.storage.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.core.provider.ToolInvocationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

/**
 * 工具审计落库验收点：颗粒度文档 §4.2（success/error_message 两列真实存在且正确落值）。
 *
 * <p>The DDL below mirrors the tool_invocations section of nivroos-boot schema.sql and must stay in
 * sync with it; the canonical script is exercised by the boot startup validation.
 */
@DataJpaTest(
    properties = {
      // cache=shared：内存库跨 Hikari 连接共享（:memory: 每连接独立会丢表）
      "spring.datasource.url=jdbc:sqlite:file:toolinv-test?mode=memory&cache=shared",
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.jpa.hibernate.ddl-auto=none"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ToolInvocationStoreConfiguration.class)
@Sql(
    statements =
        """
    CREATE TABLE IF NOT EXISTS tool_invocations (
        id            INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id    VARCHAR(255),
        tool_name     VARCHAR(64)  NOT NULL,
        input_json    TEXT,
        result_json   TEXT,
        success       BOOLEAN      NOT NULL,
        error_message TEXT,
        duration_ms   BIGINT       NOT NULL,
        created_at    TIMESTAMP    NOT NULL
    );
    """)
class JpaToolInvocationStoreTest {

  @Autowired ToolInvocationStore store;

  @Autowired ToolInvocationRepository repository;

  @Test
  @DisplayName("成功执行落库：success=true、result 与 session 关联正确")
  void recordSuccess_roundTripsFields() {
    store.record(
        "s-1", "http_get", "{\"url\":\"https://wttr.in\"}", "{\"temp\":15}", true, null, 42L);

    var row = repository.findAll().get(0);
    assertThat(row.getSessionId()).isEqualTo("s-1");
    assertThat(row.getToolName()).isEqualTo("http_get");
    assertThat(row.getInputJson()).contains("wttr.in");
    assertThat(row.getResultJson()).contains("15");
    assertThat(row.getSuccess()).isTrue();
    assertThat(row.getErrorMessage()).isNull();
    assertThat(row.getDurationMs()).isEqualTo(42L);
    assertThat(row.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("失败/拒绝落库：success=false 且 error_message 真实落值")
  void recordFailure_storesErrorColumn() {
    store.record(
        "s-1",
        "http_get",
        "{\"url\":\"https://evil.com\"}",
        null,
        false,
        "Sandbox violation: domain not allowed: evil.com",
        7L);

    var row = repository.findAll().get(0);
    assertThat(row.getSuccess()).isFalse();
    assertThat(row.getResultJson()).isNull();
    assertThat(row.getErrorMessage()).contains("evil.com");
  }

  @Test
  @DisplayName("多次执行逐条落库，不覆盖")
  void repeatedRecords_accumulateRows() {
    store.record("s-1", "http_get", "{}", "r1", true, null, 10L);
    store.record("s-1", "http_get", "{}", null, false, "rejected", 20L);

    assertThat(repository.count()).isEqualTo(2);
  }
}
