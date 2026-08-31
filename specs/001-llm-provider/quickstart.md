# Quickstart 验证指南：US-1 对接 LLM

本 US 无独立可见入口（联合验收锚点为 SC-005 的 Demo 一，与 US-2 合跑），
US-1 阶段的验证以自动测试 + 配置校验 + 审计落库核对为主。

## 前提

- JDK 21、Maven 3.9+
- DeepSeek 与 Kimi（Moonshot）两个 API key；Kimi 走 OpenAI 兼容通道
  （research §1）

## 步骤

1. **设置凭证**：

   ```bash
   export DEEPSEEK_API_KEY=sk-xxx
   export KIMI_API_KEY=sk-xxx
   ```

2. **构建**：

   ```bash
   mvn clean package
   ```

3. **核对配置**：确认 `nivroos-boot/src/main/resources/application.yaml` 中
   `nivroos.providers` 下 deepseek 与 kimi 两段的 `api-key` 均为
   `${..._API_KEY}` 占位。

4. **自动验证**：

   ```bash
   mvn test -pl nivroos-provider
   ```

   - 无 key：集成测试自动跳过（环境守卫），单元测试全绿；
   - 有 key：DeepSeek 与 Kimi 集成测试各执行一次真实调用并断言返回内容非空
    （任一家缺 key 时该家测试自动跳过，不红）。

5. **审计落库核对**（有 key 时）：集成测试断言 + 直接查库（核心阶段不做查询接口）：

   ```text
   llm_calls 表新增一行：provider=deepseek、model=deepseek-chat、
   token 三列非空、duration_ms>0、created_at 为调用完成时间
   ```

## 负向验证（FR-006/007/010）

| 操作 | 预期结果 |
| --- | --- |
| 删除 `DEEPSEEK_API_KEY` 后启动/调用 | 报错并指明缺失的环境变量名 |
| Profile 引用未注册的供应商名 | `ProviderNotFoundException`，消息含可用供应商列表 |
| `api-key` 写明文 | 启动配置校验拒绝，指明键路径 |
| 配置两个同名供应商 | 配置校验拒绝并指出重复名称 |
| 厂商请求超时/失败 | `ProviderCallException`，不自动切换（FR-006） |

## 已知限制

- `llm_calls.session_id` 本阶段为空（US-2 起填充）；
- Kimi 无官方 GA starter（research §1），经 OpenAI 兼容通道接入（官方 openai
  starter + Moonshot 兼容端点）；模型名以 Moonshot 官方当前口径配置时核实。

## 联合验收（SC-005）

US-2 完成后合跑 Demo 一（`nivroos chat` 查天气穿衣），验收锚点见 spec.md SC-005。
