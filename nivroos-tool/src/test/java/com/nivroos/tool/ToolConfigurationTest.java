package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.nivroos.core.memory.MemoryService;
import com.nivroos.core.notify.NotifyChannelStore;
import com.nivroos.memory.MemoryTools;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.SandboxAction;
import com.nivroos.tool.sandbox.SandboxViolationException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** ToolConfiguration 验收点：颗粒度文档 §4.2（三组白名单绑定 / 空白名单全拒 / shell 超时 / 注册表内容）。 */
class ToolConfigurationTest {

  @TempDir Path tempDir;

  /** 配置键逐字对齐 application.yml：file.allowed_paths / shell.allowed_commands / http.allowed_domains。 */
  private MockEnvironment environmentWithWhitelists() {
    return new MockEnvironment()
        .withProperty("file.allowed_paths[0]", tempDir.toString())
        .withProperty("shell.allowed_commands[0]", "echo")
        .withProperty("http.allowed_domains[0]", "wttr.in");
  }

  @Test
  @DisplayName("三组白名单经 Binder 绑定：放行白名单内目标、拒绝白名单外目标")
  void sandbox_bindsThreeWhitelistsFromConfigKeys() {
    Sandbox sandbox = new ToolConfiguration(environmentWithWhitelists()).sandbox();

    assertThatCode(() -> sandbox.enforce(fileAction(SandboxAction.ActionType.FILE_READ, "a.md")))
        .doesNotThrowAnyException();
    assertThatCode(() -> sandbox.enforce(commandAction("echo hello"))).doesNotThrowAnyException();
    assertThatCode(() -> sandbox.enforce(httpAction("https://wttr.in/Beijing")))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () -> sandbox.enforce(fileAction(SandboxAction.ActionType.FILE_READ, "/etc/passwd")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(() -> sandbox.enforce(commandAction("rm -rf /")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(() -> sandbox.enforce(httpAction("https://evil.example.com")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("三组白名单全空（未配置）→ 四类动作全拒，不是不校验")
  void sandbox_emptyWhitelists_denyEveryActionType() {
    Sandbox sandbox = new ToolConfiguration(new MockEnvironment()).sandbox();

    assertThatThrownBy(
            () -> sandbox.enforce(fileAction(SandboxAction.ActionType.FILE_READ, "a.md")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(
            () -> sandbox.enforce(fileAction(SandboxAction.ActionType.FILE_WRITE, "a.md")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(() -> sandbox.enforce(commandAction("echo hi")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(() -> sandbox.enforce(httpAction("https://wttr.in/Beijing")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("shell 超时：未配置时取缺省 30 秒，配置后取值生效")
  void shellTimeout_defaultsAndReadsConfiguredValue() {
    assertThat(new ToolConfiguration(new MockEnvironment()).shellTimeout())
        .isEqualTo(Duration.ofSeconds(30));
    assertThat(
            new ToolConfiguration(new MockEnvironment().withProperty("shell.timeout-seconds", "5"))
                .shellTimeout())
        .isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("ToolRegistry Bean 扫容器：内置文件工具与 nivroos-memory 的 MemoryTools 都进注册表，无注解 Bean 不干扰")
  void toolRegistry_scansAnnotatedBeansFromContainer() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      // 无 @Tool 方法的普通 Bean 也在容器里：扫描必须先过滤再交给 Spring AI（否则其 provider 抛异常）
      context.registerBean("memoryService", MemoryService.class, () -> mock(MemoryService.class));
      context.registerBean(
          "memoryTools",
          MemoryTools.class,
          () -> new MemoryTools(context.getBean(MemoryService.class)));
      // 渠道注册表的实现在 nivroos-storage（依赖倒置），本模块只依赖 core 接口 → 测试注入 mock
      context.registerBean(
          "notifyChannelStore", NotifyChannelStore.class, () -> mock(NotifyChannelStore.class));
      context.register(ToolConfiguration.class);
      context.refresh();

      ToolRegistry registry = context.getBean(ToolRegistry.class);

      assertThat(registry.all())
          .containsOnlyKeys(
              "read_file",
              "write_file",
              "list_dir",
              "shell",
              "http_get",
              "http_post",
              "save_memory",
              "recall_memory",
              "notify");
      assertThat(registry.get("save_memory").getInputSchema().value()).isNotBlank();
      assertThat(registry.get("nope")).isNull();
    }
  }

  @Test
  @DisplayName("依赖 ToolRegistry 的 Bean（如 CLI 的 chat 命令）不会让注册表装配陷入循环依赖")
  void toolRegistry_beanDependingOnRegistry_doesNotDeadlock() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          "notifyChannelStore", NotifyChannelStore.class, () -> mock(NotifyChannelStore.class));
      context.registerBean(
          "registryDependent",
          RegistryDependentBean.class,
          () -> new RegistryDependentBean(context.getBean(ToolRegistry.class)));
      context.register(ToolConfiguration.class);

      context.refresh();

      assertThat(context.getBean(RegistryDependentBean.class).registry().get("shell")).isNotNull();
    }
  }

  /** 模拟 ChatCommand：依赖 ToolRegistry、但自身没有 {@code @Tool} 方法的普通 Bean。 */
  static class RegistryDependentBean {

    private final ToolRegistry registry;

    RegistryDependentBean(ToolRegistry registry) {
      this.registry = registry;
    }

    ToolRegistry registry() {
      return registry;
    }
  }

  private SandboxAction fileAction(SandboxAction.ActionType type, String path) {
    return new SandboxAction(type, tempDir.resolve(path).toString());
  }

  private static SandboxAction commandAction(String command) {
    return new SandboxAction(SandboxAction.ActionType.SHELL_COMMAND, command);
  }

  private static SandboxAction httpAction(String url) {
    return new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, url);
  }
}
