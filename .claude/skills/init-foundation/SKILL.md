---
name: "init-foundation"
description: >-
  为 Java/Maven 项目（JDK 21 + Spring Boot 3.x 企业级单体）初始化工程地基：Maven 多模块骨架、
  结构化日志（dev 彩色 / prod JSON + traceId）、监控接口预留、Spring MVC 虚拟线程、OpenAPI、
  统一响应体与全局异常、Google 格式 + Checkstyle、代码安全检查（SpotBugs + Find Security Bugs + OWASP
  Dependency-Check）、pre-commit 与 CI 门禁。版本先核实后锁定。当用户要「初始化项目 / 搭工程骨架 /
  加日志监控 / 加开发规范 / 加代码安全检查」时使用。
argument-hint: "[目标目录（默认当前仓库根）]"
user-invocable: true
disable-model-invocation: false
---

# init-foundation — 初始化工程地基

把「工程地基」一次性、标准化地装好——业务逻辑（核心能力）不在本 skill 范围内，装完后走 Spec-Kit 的 user story 开发。

## 用途与触发

- 新 Java/Maven 项目（或多模块骨架期）在业务代码开始前建立工程门禁与基础模块骨架
- 触发词：初始化工程门禁、搭工程骨架、加日志监控、加 Java 规范、Google 风格、代码安全检查、质量门禁

## 不做什么（边界）

- 不实现核心能力（Provider / ReAct / Memory / Tool / Web）——那是业务模块，走 Spec-Kit 拆解
- 不硬编码任何密钥 / token / API key——一律 `${ENV_VAR}` 占位
- 不替换已存在的业务代码；只新增基础设施与配置
- 不引入 Actuator / Micrometer / Prometheus 依赖（监控属扩展阶段，见决策记录）

## 宪法级条目（不得违反）

1. **版本必须先核实后落地**：任何插件/工具版本写入 POM 前，必须用 Maven Central 目录列表核实（步骤 2 命令模板），不得凭记忆或教程摘要写版本号。
   - 违反症状：坐标 404、`PluginResolutionException`、与已锁版本矩阵冲突
   - 修复：删掉猜测值，`curl` metadata 重新锁定，并在 POM 注释标注核实日期
2. **重扫描不得进默认构建生命周期**：OWASP dependency-check 只能放独立 Maven profile（如 `-Psecurity`）或默认 skip、CI 开启。
   - 违反症状：每次 `mvn verify` 下载 NVD 库耗时分钟级，拖慢「每周末可演示」节奏
3. **格式冲突必须用 `mvn spotless:apply` 修复**，不得手改格式，apply 后重新提交（pre-commit 会拦）。
   - 违反症状：手改后 CI 与本地结果不一致、下次 apply 又产生 diff
4. **只新增、不删除、不替换**既有插件配置。
5. **日志统一 SLF4J，禁止 `System.out`**（结构化日志与审计依赖它）。
   - 违反症状：输出无时间戳/级别/MDC，绕过 logback 配置与日志采集
6. **表结构权威是 `schema.sql`**（幂等 `CREATE TABLE IF NOT EXISTS`），`hibernate.ddl-auto` 必须 `none`。
   - 违反症状：SQLite 下 Hibernate `ALTER TABLE` 报错、重启丢数据
7. **Spring AI 的 eager 自动装配必须排除**（`application.yml` 的 `autoconfigure.exclude`）。
   - 违反症状：启动即创建 `ChatModel` 并索要 api-key，绕过 Provider 显式映射
8. **每步落地后必须 `mvn verify` 验证**，失败输出如实汇报，不掩饰、不跳过。

## 决策记录（防漂移，无新证据不推翻）

- **不装阿里 P3C**：p3c-pmd 2.1.1 挂在 PMD 6.55 上，PMD 6 语法解析上限 Java 19——装它等于禁止 Java 21 语法（record patterns / pattern switch），且需手动升 ASM 补丁才能读 Java 21 字节码。编码规约由 SpotBugs + Checkstyle + 代码评审承担。
- **不装 Actuator / Micrometer / Prometheus**：监控属扩展阶段；只做 `MetricsRegistry` 接口预留（core 模块）+ 结构化日志。若文档升级把监控提前到核心阶段，再回看此条目。

## 流程（按顺序执行，每步完成后 `git commit`，原子提交）

### 步骤 0：参数确认

向用户确认：`groupId`、根 `artifactId`、模块清单、端口（默认 8080）、JDK（默认 21）。

### 步骤 1：Maven 多模块骨架（可选，已有则跳过）

建父 `pom.xml`（packaging=pom）+ 业务模块；启动模块（含 `main`）打 fat JAR。

### 步骤 2：版本核实（curl 模板）

```bash
for p in \
  com/diffplug/spotless/spotless-maven-plugin \
  com/github/spotbugs/spotbugs-maven-plugin \
  org/owasp/dependency-check-maven \
  org/apache/maven/plugins/maven-checkstyle-plugin \
  org/jacoco/jacoco-maven-plugin \
  com/google/googlejavaformat/google-java-format \
  com/h3xstream/findsecbugs/findsecbugs-plugin \
  net/logstash/logback/logstash-logback-encoder; do
  curl -s "https://repo1.maven.org/maven2/$p/maven-metadata.xml" | grep '<release>'
done
```

结果锁定到根 POM `properties`，注释标注核实日期。选 GA 不用里程碑版本。

### 步骤 3：根 POM 质量门禁（模板要点）

- **Spotless**：`check` 绑 `verify`；`googleJavaFormat` 显式固定版本
- **Checkstyle**：`check` 绑 `validate`（最快反馈）；`google_checks.xml`、`violationSeverity=error`、含测试源码
- **SpotBugs + Find Security Bugs**：`check` 绑 `verify`；`effort=Max`、`threshold=Low`；findsecbugs 作为 spotbugs 插件挂载（OWASP Top 10 模式）
- **JaCoCo**：`prepare-agent` + `report` 绑 `verify`；只报告不设覆盖率门槛（门槛属治理层）
- **`-Psecurity` profile**：`dependency-check-maven`，`failBuildOnCVSS` 7，`nvdDatafeedUrl` 指向官方 nightly cache（CI 免 NVD API key），`ossIndexAnalyzerEnabled=false`，`suppressionFile` 指向 `config/dependency-check-suppressions.xml`

### 步骤 4：工程文件

- `.editorconfig`：java 2 空格缩进（Google 风格）、UTF-8、LF、文件末尾换行
- `application.yml` 关键项：
  - `spring.threads.virtual.enabled: true`（JDK 21 虚拟线程）
  - `spring.autoconfigure.exclude` 排除 Spring AI eager 装配
  - SQLite：Hikari `journal_mode=WAL` + `busy_timeout=5000`
  - `hibernate.ddl-auto: none` + `spring.sql.init.mode: always` + `schema.sql` 占位
- `logback-spring.xml`：`springProfile` 双 profile——dev 彩色控制台 + 滚动文件；prod 单行 JSON（`LogstashEncoder`，MDC 全量进 JSON，customFields 标 application）；pattern 含 sessionId/traceId MDC
- `config/dependency-check-suppressions.xml`：空抑制模板（注释规范：抑制必须注明 CVE + 理由）

### 步骤 5：API 规范层（web 模块）

- `ApiResponse<T>(code/message/data/timestamp)` record + `ok/error` 工厂方法
- `ErrorCode` 枚举：code 语义镜像 HTTP 状态码
- `GlobalExceptionHandler`：参数类 400 / 兜底 500，响应不泄漏堆栈
- **只搭规范层，不写 Controller**：Controller 属于各 user story 业务

### 步骤 6：监控接口预留（core 模块）

- `MetricsRegistry` 接口（counter/timer）+ no-op 实现；扩展阶段换 Micrometer 实现类，接口不变
- 核心阶段可观测性 = 结构化日志 + 审计表 + 本接口

### 步骤 7：pre-commit + CI

- `.githooks/pre-commit`：`mvn -B -q spotless:check`，失败提示 `mvn spotless:apply`；安装命令 `git config core.hooksPath .githooks`
- `.github/workflows/quality-gates.yml`：push/PR → checkout + setup-java + `mvn -B verify`；重扫描不进 CI 默认流程

### 步骤 8：CLAUDE.md 固化

- 技术栈表补工程质量门禁一行（含版本 + 核实日期）
- 常见陷阱表补：格式手改、重扫描进默认构建、插件版本漂移、actuator 重复、Spring AI eager 装配、System.out 打日志
- 命令速查：`mvn verify`（门禁）/ `mvn spotless:apply`（修格式）/ `mvn -Psecurity verify`（CVE）

### 步骤 9：验证（正向 + 负向）与报告

1. `mvn spotless:apply`：存量代码一次性归一到 Google 风格
2. `mvn verify` 全绿
3. 启动实测：dev 与 prod 两个 profile 都起得来（prod 确认输出 JSON 日志）
4. 负向验证：故意写一行不规范代码 → `spotless:check` 报错；故意引入一个带 CVE 的旧依赖 → `-Psecurity` 报警
5. 如实报告：改了哪些文件、哪些模块无代码被跳过、未验证项

## 检查清单（Definition of Done）

- [ ] Maven 多模块骨架可 `mvn clean package` 出 fat JAR
- [ ] `mvn verify` = Spotless + Checkstyle + SpotBugs(findsecbugs) + 测试 + JaCoCo 全绿
- [ ] dev 彩色日志 / prod JSON 日志（含 MDC）实测可切换
- [ ] `spring.threads.virtual.enabled=true`、Spring AI eager 装配已排除
- [ ] SQLite WAL + `ddl-auto: none` + `schema.sql` 幂等初始化
- [ ] `ApiResponse` + `ErrorCode` + `GlobalExceptionHandler` 就位（无 Controller）
- [ ] `MetricsRegistry` 接口 + no-op 实现就位
- [ ] pre-commit 安装（core.hooksPath）+ CI 工作流就位
- [ ] OWASP `-Psecurity` 可跑（免 NVD key 方案），suppression 模板就位
- [ ] 敏感配置全用 `${ENV_VAR}` 占位，无明文密钥
- [ ] 版本全部经 Maven Central 核实并标注日期

## 与 constitution / Spec-Kit 的分工

- **本 skill**：把工程地基「装上」（一次性、可复用）
- **constitution（CLAUDE.md 不可违背原则）**：把硬约束「钉死」，让 AI 每次都遵守
- **CI + pre-commit**：把检查「强制执行」（机器把关，不靠人自觉）
- **Spec-Kit user story**：地基起好后，按核心能力逐个开发，`/speckit.analyze` 收口
