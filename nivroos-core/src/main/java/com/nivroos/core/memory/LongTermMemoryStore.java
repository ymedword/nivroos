package com.nivroos.core.memory;

import java.util.List;

/**
 * 长期记忆后端接口（技术方案 §5.1 的「接口墙」）。
 *
 * <p>The pluggable backend contract: swapping {@code memory.backend} swaps the implementation while
 * everything above {@link MemoryService} stays untouched (FR-013 / FR-014). Implementations MUST
 * honour four behavioural contracts: (1) <b>no caching</b> - every call re-reads the carrier, so an
 * append is visible to the next load; (2) the <b>CORE section is never truncated</b> - truncation
 * applies to ARCHIVAL only; (3) the <b>scope is supplied by the caller</b>, never guessed; (4)
 * recall is <b>keyword matching</b> - no tokenisation, no synonym expansion, no semantic rewriting.
 */
public interface LongTermMemoryStore {

  /**
   * 追加一条记忆到指定分区（带日期 header）。
   *
   * <p>Appends one entry to the given scope. Failures propagate as runtime exceptions so the caller
   * can decide between auditing (writes) and degrading (reads).
   *
   * @param content 记忆正文（自然语言，不做结构校验）
   * @param scope 目标分区，由调用方显式给定（FR-005）
   */
  void append(String content, MemoryScope scope);

  /**
   * 读取全部长期记忆：核心区全量 + 归档区截断后，核心在前、归档在后。
   *
   * <p>Reads the full CORE section followed by the truncated ARCHIVAL section. Never cached.
   *
   * @return 拼接后的记忆文本；载体不存在或为空时返回空串
   */
  String load();

  /**
   * 在归档区做关键词匹配；核心区不参与检索（FR-009）。
   *
   * <p>Keyword match over the ARCHIVAL section only.
   *
   * @param query 检索关键词
   * @return 命中的内容行；无命中返回空列表（面向用户的「无匹配」文案由工具层产出）
   */
  List<String> recallByKeyword(String query);
}
