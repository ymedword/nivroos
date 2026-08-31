package com.nivroos.storage.tool;

import com.nivroos.core.provider.ToolInvocationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ToolInvocationStore 装配（boot 组件扫描 com.nivroos 全包，本配置随类路径生效）。 */
@Configuration(proxyBeanMethods = false)
public class ToolInvocationStoreConfiguration {

  @Bean
  ToolInvocationStore toolInvocationStore(ToolInvocationRepository repository) {
    return new JpaToolInvocationStore(repository);
  }
}
