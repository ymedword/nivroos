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

-- US-2（核心能力二）：工具调用审计（需求文档 §10 九列，含 success/error_message）
CREATE TABLE IF NOT EXISTS tool_invocations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    VARCHAR(255),
    tool_name     VARCHAR(64)  NOT NULL,
    input_json    TEXT,
    result_json   TEXT,
    success       BOOLEAN      NOT NULL,
    error_message TEXT,
    duration_ms   BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);

-- US-3（核心能力三）：长期记忆的 SQLite 档后端（memory.backend: sqlite；DDL 逐字见
-- specs/003-agent-memory/data-model.md §4）。核心/归档语义与 Markdown 档一致，
-- 分区落在 scope 列上——换后端只改 memory.backend 一行，门面及以上不动。
CREATE TABLE IF NOT EXISTS memory_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scope      VARCHAR(16) NOT NULL,   -- CORE | ARCHIVAL
    content    TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL
);

-- US-4（核心能力四）：通知渠道全局注册表（技术方案 §6.8；DDL 逐字见
-- docs/us/us4-tool.md §3.4）。核心阶段注册方式 = 手工 SQL 直插，无 CRUD 端点；
-- url 即凭证，真实值只由操作者从环境变量取用后填入，不写进文档与 git。
CREATE TABLE IF NOT EXISTS notify_channels (
    name        VARCHAR(64)  PRIMARY KEY,
    type        VARCHAR(32)  NOT NULL,   -- 渠道类型（核心阶段仅 webhook）
    url         VARCHAR(512) NOT NULL,   -- webhook 地址
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL
);
