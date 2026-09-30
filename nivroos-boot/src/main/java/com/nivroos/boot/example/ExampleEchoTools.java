package com.nivroos.boot.example;

import com.nivroos.core.model.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 方式三（重代码）示例：带 {@code @Tool} 注解的 Spring Bean（技术方案 §6.5；落位裁决 2026-09-30）。
 *
 * <p>Demonstration only, not a product tool: it shows that a plain container bean gets registered
 * by the same container scan as the built-in tools, with the schema generated from the annotations
 * - that is the whole tier-3 story. Delete it once a real in-process plugin exists.
 */
@Component
public class ExampleEchoTools {

  /**
   * 原样回显输入文本（演示注解工具如何进入注册表，无副作用）。
   *
   * @param text 要回显的文本
   */
  @Tool(name = "example_echo", description = "示例工具（方式三演示）：原样回显输入文本，无副作用")
  public ToolResult exampleEcho(@ToolParam(description = "要回显的文本") String text) {
    if (text == null) {
      return new ToolResult(false, null, "example_echo 需要 text 参数", false);
    }
    return new ToolResult(true, text, null, false);
  }
}
