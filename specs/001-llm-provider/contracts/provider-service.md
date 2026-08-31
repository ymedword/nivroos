# 契约：ProviderService（core 接口，provider 模块实现）

## 接口形态

```text
interface ProviderService {
    ChatResponse call(Profile profile, ChatRequest request);
    Set<String> providerNames();
}
```

## 正项契约

1. `call` 同步阻塞返回（宪法原则七），被多虚拟线程并发调用时线程安全；
   本层不引入共享可变状态。
2. `profile.providerName` 未注册 → 抛 `ProviderNotFoundException`，
   消息含缺失名称与可用供应商列表（FR-010）。
3. 调用失败（网络错误、超时、厂商报错）→ 抛 `ProviderCallException`，
   消息含 provider / model 与原因；**不**自动切换、**不**静默重试（FR-006）。
4. 每次 `call` 完成后（成功或失败）必须经 `LlmCallStore` 写入一条 `llm_calls`
   审计记录——FR-005 / 宪法原则五，写入是 call 的强制副作用（research §8）。
5. `ChatResponse.toolCalls` 只描述模型请求的工具调用（name + arguments），
   本接口**不执行**任何工具（FR-008 / 宪法原则二）。
6. `providerNames()` 返回当前已注册供应商集合，供配置校验与
   `nivroos provider list` 使用。

## 负向契约（禁止事项）

- 禁止注册 Spring AI ToolCallback 或使用任何自动 tool 执行链路。
- 禁止按 Bean 类型扫描容器定位 ChatModel——路由必须来自显式
  `Map<String, ChatModel>`（宪法原则三）。
- 禁止把 API key、模型名硬编码在实现中——必须来自配置与 Profile。

## 依赖契约（模块边界）

- `ProviderService`、`LlmCallStore`、内部调用类型定义在 `nivroos-core`；
- 实现（SpringAiProviderService / JpaLlmCallStore）分别在 `nivroos-provider` /
  `nivroos-storage`；装配在 `nivroos-boot` 自动配置。
