package com.nivroos.core.react;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolCallRequest;
import com.nivroos.core.model.ToolResult;
import com.nivroos.core.provider.ToolInvocationStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具执行（技术方案 §4.2）。
 *
 * <p>Resolves the tool from the pool, executes it, and records every execution - success, failure,
 * or sandbox rejection - into tool_invocations (constitution: audit day one). Sandbox enforcement
 * lives inside each tool (constitution §6.2); violations surface here as RuntimeExceptions and are
 * recorded as failures. The tool pool is a plain map in US-2 and is replaced by ToolRegistry in
 * US-4.
 */
public class ToolExecutor {

  private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, NivroTool> toolPool;
  private final ToolInvocationStore audit;

  public ToolExecutor(Map<String, NivroTool> toolPool, ToolInvocationStore audit) {
    this.toolPool = Map.copyOf(toolPool);
    this.audit = audit;
  }

  /**
   * 执行一次工具调用，成功/失败都写审计。
   *
   * @param sessionId 会话关联（tool_invocations.session_id）
   * @param call 模型请求的工具调用
   * @return 执行结果（失败时 success=false 且含错误信息，不吞异常语义）
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "log 参数经 sanitizeForLog 消毒（CR/LF 剥离）；异常对象仅作堆栈附加，不进入消息文本（findsecbugs 不识别自定义消毒方法）")
  public ToolResult execute(String sessionId, ToolCallRequest call) {
    long startedAt = System.currentTimeMillis();
    NivroTool tool = toolPool.get(call.name());
    if (tool == null) {
      String error = "Unknown tool: " + call.name();
      audit.record(
          sessionId,
          call.name(),
          call.arguments(),
          null,
          false,
          error,
          System.currentTimeMillis() - startedAt);
      return new ToolResult(false, null, error, false);
    }
    try {
      ToolResult result = tool.execute(parseInput(call.arguments()));
      long duration = System.currentTimeMillis() - startedAt;
      audit.record(
          sessionId,
          call.name(),
          call.arguments(),
          result.content(),
          result.success(),
          result.errorMessage(),
          duration);
      return result;
    } catch (RuntimeException e) {
      // 失败也留痕（Sandbox 拒绝与执行异常同路），错误信息含原因；同时打运行日志（可观测性）
      long duration = System.currentTimeMillis() - startedAt;
      log.warn(
          "Tool execution failed: tool={}, sessionId={}",
          sanitizeForLog(call.name()),
          sanitizeForLog(sessionId),
          e);
      audit.record(sessionId, call.name(), call.arguments(), null, false, e.getMessage(), duration);
      return new ToolResult(false, null, e.getMessage(), false);
    }
  }

  /** 日志参数 CRLF 消毒（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }

  private static JsonNode parseInput(String arguments) {
    try {
      return arguments == null || arguments.isBlank()
          ? MAPPER.nullNode()
          : MAPPER.readTree(arguments);
    } catch (java.io.IOException e) {
      // 参数不是合法 JSON 时给空节点，工具自行决定如何处理
      return MAPPER.nullNode();
    }
  }
}
