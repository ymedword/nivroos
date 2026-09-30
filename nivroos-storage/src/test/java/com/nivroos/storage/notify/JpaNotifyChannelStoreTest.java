package com.nivroos.storage.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.core.notify.NotifyChannelStore;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

/**
 * 通知渠道注册表验收点：颗粒度文档 §4.2（建表可写可读 / findByName 命中与未命中 / 字段映射正确）。
 *
 * <p>The DDL below mirrors the notify_channels section of nivroos-boot schema.sql and must stay in
 * sync with it; the canonical script is exercised by the boot startup validation.
 */
@DataJpaTest(
    properties = {
      // cache=shared：内存库跨 Hikari 连接共享（:memory: 每连接独立会丢表）
      "spring.datasource.url=jdbc:sqlite:file:notify-test?mode=memory&cache=shared",
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.jpa.hibernate.ddl-auto=none"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotifyChannelStoreConfiguration.class)
@Sql(
    statements =
        """
    CREATE TABLE IF NOT EXISTS notify_channels (
        name        VARCHAR(64)  PRIMARY KEY,
        type        VARCHAR(32)  NOT NULL,
        url         VARCHAR(512) NOT NULL,
        description VARCHAR(255),
        created_at  TIMESTAMP    NOT NULL
    );
    """)
class JpaNotifyChannelStoreTest {

  @Autowired NotifyChannelStore store;

  @Autowired NotifyChannelRepository repository;

  @Test
  @DisplayName("手工直插的渠道行可读：type / url / description 逐字段映射到 core record")
  void findByExistingName_mapsAllFields() {
    insert("ops-team", "webhook", "https://qyapi.example.com/hook", "运维值班群");

    var channel = store.findByName("ops-team");

    assertThat(channel).isPresent();
    assertThat(channel.get().name()).isEqualTo("ops-team");
    assertThat(channel.get().type()).isEqualTo("webhook");
    assertThat(channel.get().url()).isEqualTo("https://qyapi.example.com/hook");
    assertThat(channel.get().description()).isEqualTo("运维值班群");
  }

  @Test
  @DisplayName("渠道名不存在 → 返回空 Optional（不抛异常，由调用方回填可用渠道名）")
  void findByUnknownName_returnsEmpty() {
    insert("ops-team", "webhook", "https://qyapi.example.com/hook", null);

    assertThat(store.findByName("nope")).isEmpty();
  }

  @Test
  @DisplayName("channelNames 返回全部注册名且有序（失败信息里的可用渠道清单）")
  void channelNames_returnsAllNamesSorted() {
    insert("ops-team", "webhook", "https://qyapi.example.com/hook", null);
    insert("dev-team", "webhook", "https://qyapi.example.com/dev", "研发群");

    assertThat(store.channelNames()).containsExactly("dev-team", "ops-team");
  }

  private void insert(String name, String type, String url, String description) {
    NotifyChannelEntity entity = new NotifyChannelEntity();
    entity.setName(name);
    entity.setType(type);
    entity.setUrl(url);
    entity.setDescription(description);
    entity.setCreatedAt(LocalDateTime.now());
    repository.save(entity);
  }
}
