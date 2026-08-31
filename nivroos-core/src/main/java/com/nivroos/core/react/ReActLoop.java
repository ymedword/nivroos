package com.nivroos.core.react;

import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.ChatResponse;
import com.nivroos.core.model.Message;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolCallRequest;
import com.nivroos.core.model.ToolResult;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileContext;
import com.nivroos.core.provider.ProviderService;
import com.nivroos.core.session.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct 循环（宪法原则一：自实现，不用 Spring AI 的 Agent 抽象）。
 *
 * <p>Implementation follows the granularity doc §2.3 flowchart/pseudocode block by block: append
 * user message, then per round assemble prompt, call the provider, append the response (leave a
 * trace before judging), return on no tool calls, or execute tools sequentially and feed results
 * back. Max iterations is the dead-loop backstop; the final message text was confirmed by the user
 * (spec Clarifications).
 */
public class ReActLoop {

  private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

  /** 强制结束固定文案（spec Clarifications，2026-08-31 用户确认）。 */
  public static final String MAX_ITERATIONS_MESSAGE = "任务执行时间较长，达到最大轮数，已停止继续尝试，当前结果可能不完整。";

  private final ProviderService providerService;
  private final PromptBuilder promptBuilder;
  private final ToolExecutor toolExecutor;
  private final Map<String, NivroTool> toolPool;

  public ReActLoop(
      ProviderService providerService,
      PromptBuilder promptBuilder,
      ToolExecutor toolExecutor,
      Map<String, NivroTool> toolPool) {
    this.providerService = providerService;
    this.promptBuilder = promptBuilder;
    this.toolExecutor = toolExecutor;
    this.toolPool = Map.copyOf(toolPool);
  }

  /**
   * 跑一轮完整循环，返回最终响应。
   *
   * @param session 会话（消息在此累积，可审计）
   * @param userMessage 用户消息
   * @return 最终响应文本（无工具调用时）或强制结束固定文案
   */
  public String run(Session session, String userMessage) {
    session.appendMessage(new Message("user", userMessage));
    Profile profile = ProfileContext.current();
    int maxIterations = profile != null ? profile.getSettings().getMaxIterations() : 10;

    for (int i = 0; i < maxIterations; i++) {
      List<NivroTool> tools = resolveTools(profile);
      ChatRequest request = promptBuilder.build(session, tools);
      ChatResponse response = providerService.call(profile, request);
      // 先留痕再判断：每轮响应都进会话，事后可审计
      session.appendMessage(new Message("assistant", response.content()));

      if (response.toolCalls().isEmpty()) {
        return response.content();
      }
      // 多工具调用按顺序执行，不并行（FR-009）
      for (ToolCallRequest call : response.toolCalls()) {
        ToolResult result = toolExecutor.execute(session.getSessionId(), call);
        // 失败时回填错误信息而非空内容，让模型看到失败原因（2026-08-31 Demo 实测）
        String toolMessage = result.content() != null ? result.content() : result.errorMessage();
        session.appendMessage(new Message("tool", toolMessage));
      }
    }
    log.warn("max_iterations reached: sessionId={}", sanitizeForLog(session.getSessionId()));
    return MAX_ITERATIONS_MESSAGE;
  }

  /** 按 Profile.tools 名称从工具池解析；未注册的名字告警跳过（不静默吞）。 */
  private List<NivroTool> resolveTools(Profile profile) {
    if (profile == null || profile.getTools() == null || profile.getTools().isEmpty()) {
      return List.of();
    }
    List<NivroTool> resolved = new ArrayList<>();
    for (String name : profile.getTools()) {
      NivroTool tool = toolPool.get(name);
      if (tool == null) {
        log.warn("Tool configured in profile but not registered: {}", sanitizeForLog(name));
        continue;
      }
      resolved.add(tool);
    }
    return resolved;
  }

  /** 日志参数 CRLF 消毒：会话标识/工具名来自配置与模型，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
