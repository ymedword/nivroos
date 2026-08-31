package com.nivroos.channel.cli;

import com.nivroos.core.react.AgentService;
import com.nivroos.core.session.Session;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * CLI Channel（技术方案 §8.4；核心阶段唯一内置 Channel）。
 *
 * <p>Reads stdin line by line, feeds each line to AgentService, prints the final response; {@code
 * /quit} exits. Errors print a clear message without stack traces and keep the process alive
 * (contracts/cli-commands.md).
 */
public class CliChannel {

  public static final String QUIT_COMMAND = "/quit";

  /**
   * 启动交互循环。
   *
   * @param session 当前会话（由调用方经 SessionManager 取得）
   * @param agentService 统一入口
   */
  public void start(Session session, AgentService agentService) throws IOException {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    PrintWriter writer = new PrintWriter(System.out, true);

    writer.println(
        "NivroOS chat — Agent: " + session.getProfileName() + "（输入 " + QUIT_COMMAND + " 退出）");
    String line;
    while ((line = reader.readLine()) != null) {
      String trimmed = line.trim();
      if (QUIT_COMMAND.equals(trimmed)) {
        writer.println("再见。");
        return;
      }
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        String reply = agentService.process(session, trimmed);
        writer.println(reply);
      } catch (RuntimeException e) {
        // 清晰错误不堆栈，进程存活（契约 cli-commands.md 第 5 条）
        writer.println("错误: " + e.getMessage());
      }
    }
  }
}
