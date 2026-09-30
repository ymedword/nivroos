package com.nivroos.core.memory;

/**
 * 长期记忆分区（技术方案 §5.1）。
 *
 * <p>Memory scope: the two-way split is a semantic that stays stable across every storage backend -
 * CORE is injected in full on every round and is never truncated, ARCHIVAL is truncatable and is
 * the only scope searched by keyword recall. 分区语义跨后端稳定，不随存储形式变化（spec FR-002）。
 */
public enum MemoryScope {

  /** 核心区：每轮全量注入、永不截断、不参与检索。 */
  CORE,

  /** 归档区：可按 {@code memory.archive-max-chars} 截断（保留最新）、可被关键词检索。 */
  ARCHIVAL
}
