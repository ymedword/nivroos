package com.nivroos.storage.llm;

import org.springframework.data.jpa.repository.JpaRepository;

/** llm_calls 审计仓储（核心阶段只写，查询接口属扩展阶段）。 */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {}
