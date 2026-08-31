package com.nivroos.storage.tool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 工具调用审计记录（需求文档 §10，九列，不加不减）。
 *
 * <p>Audit row for one tool execution; unlike llm_calls this table DOES carry success/error_message
 * columns (authoritative schema). Records are write-once.
 */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "tool_name", nullable = false)
  private String toolName;

  @Column(name = "input_json", columnDefinition = "TEXT")
  private String inputJson;

  @Column(name = "result_json", columnDefinition = "TEXT")
  private String resultJson;

  @Column(name = "success", nullable = false)
  private Boolean success;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "duration_ms", nullable = false)
  private Long durationMs;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getToolName() {
    return toolName;
  }

  public void setToolName(String toolName) {
    this.toolName = toolName;
  }

  public String getInputJson() {
    return inputJson;
  }

  public void setInputJson(String inputJson) {
    this.inputJson = inputJson;
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String resultJson) {
    this.resultJson = resultJson;
  }

  public Boolean getSuccess() {
    return success;
  }

  public void setSuccess(Boolean success) {
    this.success = success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
