package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nivroos.core.model.NivroTool;
import com.nivroos.tool.sandbox.WhitelistSandbox;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 工具对外契约（颗粒度文档 §4.2）：不管来源是内置 / 方式三 / MCP，进注册表后名称、描述、输入 schema 都不得为空。
 *
 * <p>Contract test written against the registry, not against individual tools, because the failure
 * mode is global: the provider layer feeds {@code tool.getInputSchema().value()} straight into the
 * LLM request, so a single empty schema breaks every call of that turn. 三类来源在测试内各注册一个， 一次遍历全部覆盖。
 */
class ToolContractTest {

  @Test
  @DisplayName("三类来源（内置 / 方式三 / MCP）各注册一个：一次遍历即覆盖全部来源")
  void registry_containsAllThreeToolSources() {
    ToolRegistry registry = registryWithAllThreeSources();

    assertThat(registry.all())
        .containsKeys("read_file", "ops_report", "list_prs") // 内置、方式三、MCP 各一代表
        .allSatisfy((key, tool) -> assertThat(tool.getName()).isEqualTo(key));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allRegisteredTools")
  @DisplayName("每个注册工具的名称 / 描述 / 输入 schema 都非空——任一为空会让全部 LLM 调用失败")
  void everyTool_exposesNonBlankNameDescriptionAndSchema(NivroTool tool) {
    assertThat(tool.getName()).isNotBlank();
    assertThat(tool.getDescription()).isNotBlank();
    assertThat(tool.getInputSchema()).isNotNull();
    assertThat(tool.getInputSchema().value()).isNotBlank();
  }

  /** 三类来源各注册一个后返回注册表；每个测试方法各自建一份，互不共享状态。 */
  private static ToolRegistry registryWithAllThreeSources() {
    ToolRegistry registry = new ToolRegistry();
    // 内置档：真实 FileTools Bean，走 @Tool 扫描路径（与方式三同一条注册路径，§6.6）
    registry.scanAnnotated(new FileTools(new WhitelistSandbox(List.of(), List.of(), List.of())));
    // 方式三档：用户自己写的注解组件（测试内最小样本，真实形态见 nivroos-boot 的示例 Bean）
    registry.scanAnnotated(new OpsReportTool());
    // MCP 档：经 register 注册的适配器（server 已于启动时拉取工具列表）
    registry.register(
        new McpToolAdapter(
            "github-mcp",
            McpSchema.Tool.builder()
                .name("list_prs")
                .description("列出仓库的 PR")
                .inputSchema(
                    new McpSchema.JsonSchema(
                        "object",
                        Map.of("state", Map.of("type", "string")),
                        List.of(),
                        Boolean.FALSE,
                        null,
                        null))
                .build(),
            mock(McpSyncClient.class)));
    return registry;
  }

  private static Stream<Arguments> allRegisteredTools() {
    return registryWithAllThreeSources().all().values().stream().map(Arguments::of);
  }

  /** 方式三最小样本：任意 Java 组件上的 {@code @Tool} 方法。 */
  static class OpsReportTool {

    @Tool(name = "ops_report", description = "汇总当日运维报告")
    public String opsReport(@ToolParam(description = "报告日期") String date) {
      return "ops report for " + date;
    }
  }
}
