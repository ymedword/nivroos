package com.nivroos.core.react;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileContext;
import com.nivroos.core.profile.ProfileRegistry;
import com.nivroos.core.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 统一入口验收点：颗粒度文档 §4.2/§4.3（FR-006）。 */
class AgentServiceTest {

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
  }

  @Test
  @DisplayName("正常路径：设置 Profile 上下文、委托循环、结束清理")
  void process_setsAndClearsContext() {
    ReActLoop loop = mock(ReActLoop.class);
    when(loop.run(any(), any())).thenReturn("回答");
    ProfileRegistry registry = registryWithWeather();
    AgentService service = new AgentService(loop, registry);

    String reply = service.process(session(), "你好");

    assertEquals("回答", reply);
    assertNull(ProfileContext.current()); // finally 清理
  }

  @Test
  @DisplayName("验收点：处理中抛异常也必须清掉 ProfileContext（虚拟线程复用防串号）")
  void process_whenLoopThrows_clearsProfileContext() {
    ReActLoop loop = mock(ReActLoop.class);
    when(loop.run(any(), any())).thenThrow(new RuntimeException("boom"));
    AgentService service = new AgentService(loop, registryWithWeather());

    assertThrows(RuntimeException.class, () -> service.process(session(), "hi"));

    assertNull(ProfileContext.current()); // 没清的话，复用线程的下一个请求会拿到别人的 Profile
  }

  @Test
  @DisplayName("执行期间 ProfileContext 可读取到当前 Agent")
  void process_exposesProfileDuringRun() {
    ProfileRegistry registry = registryWithWeather();
    ReActLoop loop = mock(ReActLoop.class);
    when(loop.run(any(), any()))
        .thenAnswer(
            invocation -> {
              assertEquals("weather", ProfileContext.current().getName());
              return "ok";
            });
    AgentService service = new AgentService(loop, registry);

    service.process(session(), "hi");
  }

  private static ProfileRegistry registryWithWeather() {
    Profile profile = new Profile();
    profile.setName("weather");
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(profile);
    return registry;
  }

  private static Session session() {
    return new Session("cli:u:weather", "weather", "cli", "u");
  }
}
