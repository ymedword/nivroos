package com.nivroos.storage.notify;

import com.nivroos.core.notify.NotifyChannel;
import com.nivroos.core.notify.NotifyChannelStore;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * NotifyChannelStore 的 JPA 实现（依赖倒置：接口在 core，实现在 storage，同 JpaToolInvocationStore）。
 *
 * <p>Maps the JPA row to the core record so callers never see the entity. Names come back sorted so
 * the failure message that backfills them reads the same on every run.
 */
public class JpaNotifyChannelStore implements NotifyChannelStore {

  private final NotifyChannelRepository repository;

  public JpaNotifyChannelStore(NotifyChannelRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<NotifyChannel> findByName(String name) {
    return repository.findById(name).map(JpaNotifyChannelStore::toRecord);
  }

  @Override
  public List<String> channelNames() {
    return repository.findAll().stream()
        .map(NotifyChannelEntity::getName)
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  private static NotifyChannel toRecord(NotifyChannelEntity entity) {
    return new NotifyChannel(
        entity.getName(), entity.getType(), entity.getUrl(), entity.getDescription());
  }
}
