package com.nivroos;

import com.nivroos.cli.NivroOsCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * NivroOS bootstrap — unified entry point for the single executable JAR.
 *
 * <p>Dispatch: CLI commands ({@code version} and later {@code init} / {@code chat} / ...) run
 * through the Picocli entry in {@code nivroos-cli} without starting Spring (fast); anything else
 * starts the Spring Boot application ({@code nivroos serve} / no args). Per the constitution: JDK
 * 21 + Spring Boot 3.x monolith. Spring AI Alibaba is used for protocol conversion and
 * {@code @Tool} schema generation only; the agent core is the self-implemented ReAct loop.
 */
@SpringBootApplication
public class NivroOsApplication {

  public static void main(String[] args) {
    // 轻命令直接走 CLI（不启动 Spring，启动快）
    if (args.length > 0 && NivroOsCli.isCliCommand(args[0])) {
      System.exit(NivroOsCli.run(args));
    }
    // chat 需要 Spring 上下文（ProviderService / 审计 bean），由 ChatCommand 在上下文内执行
    if (args.length > 0 && "chat".equals(args[0])) {
      ConfigurableApplicationContext context = SpringApplication.run(NivroOsApplication.class);
      int exitCode = context.getBean(com.nivroos.cli.ChatCommand.class).execute(args);
      context.close();
      System.exit(exitCode);
    }
    SpringApplication.run(NivroOsApplication.class, args);
  }
}
