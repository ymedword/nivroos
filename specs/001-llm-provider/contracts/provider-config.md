# 契约：Provider 配置（application.yaml 的 nivroos.providers 段）

## 结构

```yaml
nivroos:
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}        # 必须为 ${ENV_VAR} 占位，明文直接校验拒绝
      base-url: https://api.deepseek.com   # 可选
```

## 约束

1. 供应商名称 = YAML 键，全局唯一；重复键由配置绑定校验显式拒绝并指出名称
   （FR-009）。
2. `api-key` 必填；值必须以 `${` 开头（环境变量占位），否则配置校验失败并指明
   具体键路径（FR-003 / FR-007）。
3. `base-url` 可选；缺失时走所选 starter 的默认端点。
4. 模型**不进**本配置段——模型由 Profile（`AGENT.md` frontmatter 的
   `provider.model`）指定（技术方案 §8.2 权威裁决）。
5. 占位引用的环境变量未设置 → 启动校验报错并指明缺失的环境变量名（FR-007）。

## 校验时机

- 装配期（Spring 上下文启动）：ProviderProperties 绑定后即校验，非法配置直接
  拒绝启动并输出指明具体项的错误——不静默失败。

## 双通道（FR-003）

- 通道一：环境变量占位（`${ENV_VAR}`），application.yml 中的默认形态。
- 通道二：独立本地配置文件 `nivroos-secrets.yml`（运行目录下，`optional:file:`
  导入，根 `.gitignore` 排除，绝不入库）。该文件中的明文 api-key 会被校验放行；
  **application.yml 里的明文仍然拒绝**。两通道并存时文件值优先于占位符。
  实现细节：明文检查按原始配置值 + 来源文件判断（2026-08-31 实测：Boot 3.5
  绑定器对 `${ENV_VAR}` 保持字面量，无法在绑定值上区分来源）。
