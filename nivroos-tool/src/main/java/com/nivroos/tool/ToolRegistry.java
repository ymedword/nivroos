package com.nivroos.tool;

import com.nivroos.core.model.NivroTool;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

/**
 * 运行期工具登记表（技术方案 §6.6）。
 *
 * <p>One catalog for all three tool sources: built-in tools and plain {@code @Tool} beans share
 * {@link #scanAnnotated} (the same registration path, §6.6), MCP tools arrive through {@link
 * #register}. Written during startup、运行期只读。逐轮按 {@code Profile.tools} 过滤仍留在 {@code
 * ReActLoop.resolveTools}（US-2 已交付、签名不变）——core 不反向依赖本模块。
 */
public class ToolRegistry {

  private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

  /** 启动期写入、运行期只读；并发容器只为装配期多 Bean 写入的可见性兜底。 */
  private final Map<String, NivroTool> tools = new ConcurrentHashMap<>();

  /**
   * 按名注册（MCP 工具与扫描路径共用）。
   *
   * <p>Duplicate names keep the first registration and only WARN: a subprocess MCP server must not
   * be able to silently shadow a built-in tool（内置优先）。
   */
  public void register(NivroTool tool) {
    NivroTool existing = tools.putIfAbsent(tool.getName(), tool);
    if (existing != null) {
      log.warn(
          "duplicate tool name ignored, keeping the first: name={}",
          sanitizeForLog(tool.getName()));
    }
  }

  /** 按名查找；未注册返回 null 不抛（ReActLoop 据此 WARN 跳过）。 */
  public NivroTool get(String name) {
    return name == null ? null : tools.get(name);
  }

  /** 全量快照（不可变），装配进 ReActLoop / ToolExecutor。 */
  public Map<String, NivroTool> all() {
    return Map.copyOf(tools);
  }

  /**
   * 扫描 {@code @Tool} 标注的 Bean 并逐个注册（内置 Tool 与方式三 Bean 共用这一条路径）。
   *
   * <p>Schema generation only - discovery and parameter binding come from Spring AI, execution
   * stays with ToolExecutor（宪法原则二：不引入自动执行路径）。没有 {@code @Tool} 方法的 Bean 静默跳过，不产生注册项（见 {@link
   * #hasToolMethod}）。
   */
  public void scanAnnotated(Object... beanCandidates) {
    if (beanCandidates == null || beanCandidates.length == 0) {
      return;
    }
    Object[] annotated =
        Arrays.stream(beanCandidates).filter(ToolRegistry::hasToolMethod).toArray();
    if (annotated.length == 0) {
      return;
    }
    ToolCallbackProvider provider =
        MethodToolCallbackProvider.builder().toolObjects(annotated).build();
    for (ToolCallback callback : provider.getToolCallbacks()) {
      register(new AnnotatedToolAdapter(callback));
    }
  }

  /** 日志参数 CRLF 消毒：工具名可能来自 MCP server，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }

  /**
   * Spring AI 对"一个 {@code @Tool} 方法都没有的 Bean"是抛异常而不是跳过；装配侧传入的是整容器 Bean，绝大多数与工具无关，故在这里先按注解筛掉——发现语义与
   * Provider 一致（同样的目标类与 注解查找方式），避免用异常做控制流。
   */
  private static boolean hasToolMethod(Object candidate) {
    if (candidate == null) {
      return false;
    }
    Class<?> targetClass = AopUtils.getTargetClass(candidate);
    return Arrays.stream(ReflectionUtils.getDeclaredMethods(targetClass))
        .anyMatch(method -> AnnotationUtils.findAnnotation(method, Tool.class) != null);
  }
}
