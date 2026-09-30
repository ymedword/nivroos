package com.nivroos.storage.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 通知渠道注册表行（技术方案 §6.8；DDL 见 schema.sql 的 notify_channels）。
 *
 * <p>Named {@code ...Entity} on purpose: core already ships a {@link
 * com.nivroos.core.notify.NotifyChannel} record, and the JPA row is a different thing (it carries
 * the audit column created_at). Registry is global, keyed by channel name - the model references a
 * channel by name only, so the webhook url never reaches conversation history.
 */
@Entity
@Table(name = "notify_channels")
public class NotifyChannelEntity {

  @Id
  @Column(name = "name", length = 64)
  private String name;

  @Column(name = "type", nullable = false, length = 32)
  private String type;

  @Column(name = "url", nullable = false, length = 512)
  private String url;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
