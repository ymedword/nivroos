package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nivroos.core.model.JsonSchema;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolResult;
import com.nivroos.tool.sandbox.SandboxViolationException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 注册表行为与扫描路径验收点：颗粒度文档 §4.2（FR-004；对外契约断言归 ToolContractTest）。 */
class ToolRegistryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ToolRegistry registry = new ToolRegistry();

  @Test
  @DisplayName("扫描注册：内置 Tool 与方式三 Bean 走同一条路径，工具名取自注解而非方法名")
  void scanAnnotated_registersBuiltinAndAnnotatedBeansOnTheSamePath() {
    registry.scanAnnotated(new BuiltinStyleTools(), new PluginStyleTools());

    assertThat(registry.get("builtin_style")).isNotNull();
    assertThat(registry.get("plugin_style")).isNotNull();
    assertThat(registry.all()).containsOnlyKeys("builtin_style", "plugin_style");
  }

  @Test
  @DisplayName("扫描注册的工具可直接执行：MethodToolCallback 的结果还原为 ToolResult")
  void execute_annotatedTool_roundTripsToolResult() {
    registry.scanAnnotated(new PluginStyleTools());

    ToolResult result =
        registry.get("plugin_style").execute(MAPPER.createObjectNode().put("text", "hi"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("hi");
  }

  @Test
  @DisplayName("注解方法抛出的异常解包后原样上抛：沙箱拒绝的类型与文案不被包装异常顶掉")
  void execute_annotatedToolThrows_unwrapsOriginalException() {
    registry.scanAnnotated(new PluginStyleTools());

    assertThatThrownBy(
            () ->
                registry.get("plugin_style").execute(MAPPER.createObjectNode().put("text", "boom")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("boom");
  }

  @Test
  @DisplayName("@Tool 扫描：名称/描述/JSON Schema 来自注解")
  void scanAnnotated_metadataComesFromAnnotation() {
    registry.scanAnnotated(new PluginStyleTools());

    NivroTool tool = registry.get("plugin_style");
    assertThat(tool.getDescription()).isEqualTo("方式三演示工具");
    assertThat(tool.getInputSchema().value()).contains("text");
  }

  @Test
  @DisplayName("无 @Tool 方法的 Bean 静默跳过，不产生注册项也不抛")
  void scanAnnotated_beanWithoutToolMethods_skippedSilently() {
    registry.scanAnnotated(new Object(), new PluginStyleTools());

    assertThat(registry.all()).containsOnlyKeys("plugin_style");
  }

  @Test
  @DisplayName("空扫描入参是空操作")
  void scanAnnotated_noCandidates_isNoOp() {
    registry.scanAnnotated();
    registry.scanAnnotated((Object[]) null);

    assertThat(registry.all()).isEmpty();
  }

  @Test
  @DisplayName("get：命中返回工具，未命中返回 null 不抛")
  void get_missReturnsNull() {
    registry.scanAnnotated(new PluginStyleTools());

    assertThat(registry.get("plugin_style")).isNotNull();
    assertThat(registry.get("no_such_tool")).isNull();
    assertThat(registry.get(null)).isNull();
  }

  @Test
  @DisplayName("MCP 工具经 register 入表（与扫描路径共用同一张表）")
  void register_mcpTool_landsInSameTable() {
    registry.register(stubTool("github_search_prs"));
    registry.scanAnnotated(new PluginStyleTools());

    assertThat(registry.all()).containsOnlyKeys("github_search_prs", "plugin_style");
  }

  @Test
  @DisplayName("工具重名：保留先注册者不覆盖，并记 WARN（子进程工具不得静默顶掉内置工具）")
  void register_duplicateName_keepsFirstAndWarns() {
    registry.register(stubTool("shell", "内置 shell"));
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      registry.register(stubTool("shell", "MCP 同名工具"));
    } finally {
      detachAppender(appender);
    }

    assertThat(registry.get("shell").getDescription()).isEqualTo("内置 shell");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("shell");
            });
  }

  @Test
  @DisplayName("all 返回不可变快照，注册后旧引用不随之变化")
  void all_returnsImmutableSnapshot() {
    registry.register(stubTool("shell"));

    Map<String, NivroTool> snapshot = registry.all();
    registry.register(stubTool("git"));

    assertThat(snapshot).containsExactly(entry("shell", snapshot.get("shell")));
    assertThat(registry.all()).containsOnlyKeys("shell", "git");
    assertThatThrownBy(() -> snapshot.put("x", stubTool("x")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---------- 测试夹具 ----------

  /** 内置工具形态：@Tool 标注的 Bean（与方式三同一条扫描路径）。 */
  public static class BuiltinStyleTools {

    @Tool(name = "builtin_style", description = "内置工具形态")
    public ToolResult run(@ToolParam(description = "文本") String text) {
      return new ToolResult(true, text, null, false);
    }
  }

  /** 方式三形态：业务方自己写的 @Tool Bean。 */
  public static class PluginStyleTools {

    @Tool(name = "plugin_style", description = "方式三演示工具")
    public ToolResult run(@ToolParam(description = "文本") String text) {
      if ("boom".equals(text)) {
        throw new SandboxViolationException("boom rejected");
      }
      return new ToolResult(true, text, null, false);
    }
  }

  private static NivroTool stubTool(String name) {
    return stubTool(name, "stub");
  }

  private static NivroTool stubTool(String name, String description) {
    return new NivroTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return description;
      }

      @Override
      public JsonSchema getInputSchema() {
        return new JsonSchema("{\"type\":\"object\"}");
      }

      @Override
      public ToolResult execute(JsonNode input) {
        return new ToolResult(true, "ok", null, false);
      }
    };
  }

  /** logback-classic 经 spring-boot-starter-test 传递引入，用于断言 WARN 确实发出。 */
  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ToolRegistry.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(ToolRegistry.class)).detachAppender(appender);
  }
}
