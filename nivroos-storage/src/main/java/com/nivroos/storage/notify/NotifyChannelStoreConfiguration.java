package com.nivroos.storage.notify;

import com.nivroos.core.notify.NotifyChannelStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** NotifyChannelStore 装配（boot 组件扫描 com.nivroos 全包，本配置随类路径生效）。 */
@Configuration(proxyBeanMethods = false)
public class NotifyChannelStoreConfiguration {

  @Bean
  NotifyChannelStore notifyChannelStore(NotifyChannelRepository repository) {
    return new JpaNotifyChannelStore(repository);
  }
}
