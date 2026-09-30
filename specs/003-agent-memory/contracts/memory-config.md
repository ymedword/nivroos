# 契约：Memory 配置（US-3 对部署方可的接口）

> 配置键名是**已定字面量**，实现必须逐字保真（module-dev 软门禁 2）。
> 校验规则复用 `ProviderProperties` 的双通道先例（Us-1 契约
> `specs/001-llm-provider/contracts/provider-config.md`）。

## `application.yml` 段

```yaml
# 核心能力三（US-3）：记忆后端选择（markdown 默认）
memory:
  backend: markdown           # markdown | sqlite | mem0
  archive-max-chars: 4000     # 归档区截断阈值；核心区不受此值影响
  # mem0:                     # 仅 backend=mem0 时需要
  #   url: http://localhost:8000
  #   api-key: ${MEM0_API_KEY}
```

`application.yml` 中**只写 `backend` 与 `archive-max-chars`**（mem0 段以注释形式
留位，同颗粒度文档 §3.3）；实际使用时由部署方在本地密钥文件或环境变量中提供 mem0
凭证。

## 键契约

| 键 | 类型 | 默认 | 必填条件 | 非法取值行为 |
| --- | --- | --- | --- | --- |
| `memory.backend` | enum | `markdown` | 恒有默认 | 不在 `markdown`/`sqlite`/`mem0` 内 → **启动报错**并列出合法取值 |
| `memory.archive-max-chars` | int | `4000` | 恒有默认 | ≤ 0 → **启动报错** |
| `memory.mem0.url` | String | 空 | `backend=mem0` 时必填 | 缺失 → **启动报错** |
| `memory.mem0.api-key` | String | 空 | `backend=mem0` 时必填 | 见下「凭证规则」 |

## 凭证规则（双通道，逐条同 US-1）

1. **只允许 `${ENV_VAR}` 占位**。明文写进 `application.yml` → **启动报错**：
   `api-key must be an ${ENV_VAR} placeholder, plaintext is forbidden: memory.mem0.api-key`
2. **例外——本地密钥文件放行**：值来自 `nivroos-secrets.yml` 时接受明文
   （该文件已在根 `.gitignore` 第 64 行排除）。
3. **占位符未解析**：绑定值仍是 `${...}` 字面量 ⇒ 环境变量未设置 →
   报错**指明变量名**：`Environment variable not set: <NAME> (required by memory.mem0.api-key)`
4. **判定基准**：明文检查按**原始配置值**（绑定值已被 Spring 解析，无法区分来源）；
   缺失检查按**绑定值**。（Boot 3.5 绑定器对 `${ENV_VAR}` 保持字面量，构建客户端前
   必须 `environment.resolvePlaceholders(...)` 显式解析。）

## 后端切换契约

切换 `memory.backend` 的取值，**只允许**改动这一行配置：

| 改动 | 允许 | 禁止 |
| --- | --- | --- |
| `application.yml` 的 `memory.backend` 值 | ✅ | — |
| `MemoryService` 及以上（`PromptBuilder` / `MemoryTools` / `ReActLoop`） | — | ❌ 一个字都不许改 |
| `AGENT.md`（Agent 侧） | — | ❌ 不感知后端差异 |
| `MEMORY.md` 与 `memory_entries` 的「核心/归档」语义 | ✅ 语义不变 | ❌ 语义随后端漂移 |

> 这条契约是技术方案 §5.1「换后端只改 `memory.backend` 一行配置，MemoryService
> 以上一个字不动——接口墙的价值兑现」的可验证表述，由 `MemoryServiceTest` 与
> spec SC-004 承接。

## 后端专属配置

| 后端 | 需要 | 不需要 |
| --- | --- | --- |
| `markdown` | 无（载体路径系统固定为 `.nivroos/memory/MEMORY.md`） | mem0 段 |
| `sqlite` | 无（复用既有 `spring.datasource.*`） | mem0 段、独立连接配置 |
| `mem0` | `memory.mem0.url` + `memory.mem0.api-key` | 其余 |

**载体路径/地址一律由系统固定或由上述键给出，不经过通用文件工具的路径白名单**
（spec FR-015——它不是用户可指定的任意路径）。
