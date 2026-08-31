-- NivroOS 表结构（唯一权威，幂等：CREATE TABLE IF NOT EXISTS）。
--
-- 维护纪律（CLAUDE.md 常见陷阱表）：表结构变更 = 手动改本文件，
-- 不依赖 Hibernate ddl-auto（application.yml 已设 ddl-auto: none；
-- SQLite 的 ALTER TABLE 支持很弱）。
--
-- 五张核心表的定义见 CLAUDE.md「SQLite 核心表」：
--   sessions / tool_invocations / llm_calls / scheduled_tasks / task_executions
-- 随各 US 逐个落地（US-1：llm_calls）。

-- US-1（核心能力一）：LLM 调用审计（需求文档 §10 九列，不加不减）
CREATE TABLE IF NOT EXISTS llm_calls (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id         VARCHAR(255),
    provider           VARCHAR(64)  NOT NULL,
    model              VARCHAR(128) NOT NULL,
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    total_tokens       INTEGER,
    duration_ms        BIGINT       NOT NULL,
    created_at         TIMESTAMP    NOT NULL
);
