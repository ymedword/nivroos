package com.nivroos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.tool.McpServerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** init 模板验收点：颗粒度文档 §3.3 的 `mcp_servers.yaml` 形态与解析端逐键一致（T025）。 */
class InitCommandTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("模板原样落盘 = 未配置 MCP：不会因为模板本身起任何子进程")
  void templateAsIs_loadsNoServers() throws IOException {
    Path config = writeTemplate(InitCommand.MCP_CONFIG_TEMPLATE);

    assertThat(McpServerConfig.load(config)).isEmpty();
  }

  @Test
  @DisplayName("模板里的示例去掉注释即被解析端接受：servers / name / transport / command / env 逐键对齐")
  void templateExample_enabled_isAcceptedByParser() throws IOException {
    Path config = writeTemplate(enabledExample());

    List<McpServerConfig> servers = McpServerConfig.load(config);

    assertThat(servers).hasSize(1);
    McpServerConfig server = servers.get(0);
    assertThat(server.name()).isEqualTo("github-mcp");
    assertThat(server.transport()).isEqualTo("stdio");
    assertThat(server.executable()).isEqualTo("npx");
    assertThat(server.args()).containsExactly("-y", "@modelcontextprotocol/server-github");
    assertThat(server.env()).containsKey("GITHUB_TOKEN");
  }

  /** 取出模板里的示例块（`# servers:` 起、缩进两格以上的注释行）并去掉注释符。 */
  private static String enabledExample() {
    return InitCommand.MCP_CONFIG_TEMPLATE
        .lines()
        .filter(line -> line.startsWith("# servers:") || line.startsWith("#   "))
        .map(line -> line.substring(2))
        .collect(Collectors.joining("\n", "", "\n"))
        // ${GITHUB_TOKEN} 在测试环境未必存在，换成必然有的 PATH——本例验的是模板形态，不是环境
        .replace("${GITHUB_TOKEN}", "${PATH}");
  }

  private Path writeTemplate(String content) throws IOException {
    Path config = tempDir.resolve("mcp_servers.yaml");
    Files.writeString(config, content, StandardCharsets.UTF_8);
    return config;
  }
}
