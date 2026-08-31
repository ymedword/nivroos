# US-2 验收报告：ReAct 循环（Agent 大脑）

- **模块**：US-2 ReAct 循环（核心能力二）
- **验收执行时间**：2026-08-31 21:41（全量重跑）
- **验收人**：用户（真模型 Demo）+ Claude（harness 执行与核对）
- **依据**：spec.md（SC-001~005）、颗粒度文档 §4 验收 Harness、module-dev 步骤 7 七项证据 DoD

## 1. 门禁全绿（硬门禁）

| 门禁 | 结果 |
| --- | --- |
| `mvn clean verify`（Spotless + Checkstyle + SpotBugs(findsecbugs) + 测试 + JaCoCo） | ✅ BUILD SUCCESS |
| `mvn -pl nivroos-provider -am test -Dnivroos.secrets.file=…`（真实冒烟） | ✅ 18 测试 0 失败 0 跳过 |
| `mvn -Psecurity verify`（OWASP dependency-check） | ✅ 绿（无新增抑制） |

## 2. Harness 映射对号

10 个测试类全部存在且非空；4 个关键回归测试原样落地：

- `run_withToolCall_convergesAfterToolResult`（循环收敛）
- `run_neverConverging_stopsAtMaxIterations`（迭代上限精确轮数 + 固定文案）
- `execute_sandboxRejected_recordsFailureAudit`（Sandbox 拒绝 → success=false + error_message）
- `process_whenLoopThrows_clearsProfileContext`（ProfileContext 泄漏钉死）

## 3. 交付物存在性

颗粒度文档 §3 全部落地（core 13 类 / tool 5 类 / storage 4 类 / channel-cli+cli 3 类 /
boot 配置与建表）；前序改造点 2 项（LlmCallStore+sessionId、Profile 增补字段）。

## 4. 前序模块回归

US-1 全部测试回归绿（含改造点后的断言更新）。

## 5. 全局不变量自查（6/6）

① core 零 Spring AI import ② 双审计表成败都落 ③ 无明文 key（配置文件通道除外）④ 显式映射
⑤ 无异步模型 ⑥ 9 模块未增未拆。

## 6. Demo 一实录（SC-001，真实模型 + 真实网络）

**入口**：`nivroos chat --profile weather`（凭证经 `nivroos-secrets.yml` 双通道读取，
运行目录 `D:\code\nivroos-demo`）

**输入**：

```text
查一下北京天气并告诉我穿什么
```

**关键事件时间线**：

```text
21:41:19  Provider call ok: provider=deepseek, model=deepseek-chat, durationMs=1571   ← 第 1 轮：模型请求工具
21:41:21  http_get ok: url=https://wttr.in/北京?format=j1, status=200                 ← 工具真实执行（Sandbox 白名单放行）
21:41:25  Provider call ok: provider=deepseek, model=deepseek-chat, durationMs=4162   ← 第 2 轮：基于数据生成建议，收敛
```

**输出**（完整回复）：

```text
根据查询到的北京天气数据，我来为你提供穿衣建议。
## 北京天气情况
**当前天气（8月31日晚）**
- 温度：24°C，体感25°C
- 天气：**烟霾（Smoky haze）**，能见度较低
- 湿度：57%
- 南风，风速较小
**今日气温范围：20°C ～ 30°C**
- 白天阳光充足（晴），最高可达30°C
- 早晚稍凉，最低约20°C
- 紫外线指数高（7级），注意防晒
## 穿衣建议
**白天（中午）**
- 天气炎热（可达30°C），建议穿**短袖T恤、短裤或薄款连衣裙**
- 紫外线强，记得**戴帽子、墨镜，涂抹防晒霜**
**早晚（20-24°C）**
- 温差较大，推荐**短袖外搭一件薄款长袖衬衫或薄外套**，早晚凉快
- 下装可穿**轻薄长裤**，避免早晚受凉
**整体风格**
- 主打**清凉透气**的夏季穿搭，首选棉麻材质
- 由于有烟霾、能见度较低，外出建议**佩戴口罩**
**小结**：白天短袖清凉装 + 早晚备一件薄外套，注意防晒和防烟霾，就能舒适应对啦！
```

## 7. 审计落库核对（demo 库实测）

| 表 | 记录数 | 最新记录 |
| --- | --- | --- |
| `llm_calls` | 32 | #31 第一轮 420/62 tokens、#32 第二轮 12289/310 tokens（session 关联，成败都落） |
| `tool_invocations` | 27 | #27 `http_get` success=1、耗时 2379ms；调试期的 Sandbox 拒绝记录（success=0）如实留痕 |

## 8. 真实冒烟（两家供应商）

ProviderSmokeIT 2/2 通过、0 跳过：DeepSeek（deepseek-chat）与 Kimi（kimi-k2.6，
经 OpenAI 兼容通道 api.moonshot.cn）各一次真实调用，审计采集器均捕获留痕。

## 9. 验收过程发现并修复的 bug（6 个，已固化进 CLAUDE.md 陷阱表）

1. validate 误判 Spring 解析后的 key 为明文 → 明文检查改按原始配置值
2. Boot 3.5 绑定器不解析 `${ENV_VAR}` → 构建 ChatModel 前 `resolvePlaceholders` 显式解析
3. eager 自动装配漏排 7 个类（DeepSeek + OpenAI 全家桶）→ 排除清单补齐
4. Kimi base-url 带 `/v1` 致 404 `/v1/v1` → 去 `/v1`（OpenAiApi 自行追加）
5. `Environment.getProperty(key, List.class)` 读不到 YAML 列表 → 改用 Binder
6. null 内容消息崩第二轮循环 → 兜底空串 + 失败回填 errorMessage

另落地 **FR-003 双通道**：`nivroos-secrets.yml`（git 忽略、`optional:file:` 导入、明文只
放行该文件来源），冒烟测试支持 `-Dnivroos.secrets.file=` 指定凭证文件。

## 10. 结论与剩余事项

- **验收结论**：通过。SC-001~005 全部满足，七项 DoD 证据齐全。
- 剩余事项：无未决人工项；commit / 合并 main / 推送由用户决定。
- 下一步：`/module-doc-gen 3` 生成 US-3（Memory）模块执行颗粒度文档。
