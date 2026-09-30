package com.nivroos.storage.notify;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * notify_channels 仓储（渠道名即主键；核心阶段读取为主）。
 *
 * <p>Core phase registers channels by hand-inserted SQL, so there is no write API on the store
 * side; CRUD endpoints belong to the extension phase.
 */
public interface NotifyChannelRepository extends JpaRepository<NotifyChannelEntity, String> {}
