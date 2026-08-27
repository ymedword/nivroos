package com.nivroos;

import com.nivroos.cli.NivroOsCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
    // 轻命令直接走 CLI（不启动 Spring，启动快）；其余启动服务
    if (args.length > 0 && NivroOsCli.isCliCommand(args[0])) {
      System.exit(NivroOsCli.run(args));
    }
    SpringApplication.run(NivroOsApplication.class, args);
  }
}
