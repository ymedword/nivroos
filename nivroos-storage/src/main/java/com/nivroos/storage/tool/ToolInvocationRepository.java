package com.nivroos.storage.tool;

import org.springframework.data.jpa.repository.JpaRepository;

/** tool_invocations 审计仓储（核心阶段只写，查询接口属扩展阶段）。 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, Long> {}
