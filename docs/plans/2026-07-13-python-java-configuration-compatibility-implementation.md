# Python/Java 配置兼容实施计划

- 状态：已实现并验证
- 当前执行状态：Task C1-C5 全部完成
- 日期：2026-07-13
- Spec：[Python/Java 配置兼容设计](../specs/2026-07-13-python-java-configuration-compatibility-design.md)
- Contract：[Python/Java 配置兼容契约](../contracts/python-java-configuration.md)

本计划记录配置兼容从 Golden 到启动接入的连续实施状态。

## Task C1：配置 Golden 与 Parser 选型（已完成）

- 为 Python Golden 生成器增加配置共同投影。
- 固化现代 DeepSeek、根级旧字段、优先级、Provider 预设、环境变量和 Deferred Case。
- 调研并确定 Java TOML Parser 的版本、许可证和维护状态。
- 在 `agent-bootstrap` 增加测试范围依赖和夹具读取，不新增 Maven 模块。

聚焦验收：Python 生成器重复运行字节一致；Manifest Hash 更新经过审查。

实施结果：

- 新增 Python 配置解析共同投影 7 Case，以及已批准 Java 安全校验 5 Case。
- Manifest 记录 Python `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef` 和配置参考文件 Hash。
- 选择 `org.tomlj:tomlj:1.1.1`，Task C1 以测试 Scope 接入 `agent-bootstrap`；决策和风险见 ADR-0004。
- Java 聚焦兼容测试验证夹具可读、TOML 原生整数类型、语法错误区分和输入 Hash。
- 生成器重复运行字节一致；聚焦命令实际执行 Manifest 1 Test、配置夹具 2 Tests，全部通过。

## Task C2：只读文档与 Resolver（已完成）

- 先用测试固定文件定位、双模式和来源优先级。
- 实现只读 `ConfigurationDocument`、类型化活动快照和结构化诊断。
- 实现现代/旧字段、Provider 预设、严格类型和 API Key 占位符规则。
- 确认未知/Deferred 字段不会失败，原配置文件字节不变。

聚焦验收：只运行 `agent-bootstrap` 配置 Resolver 测试一次 RED、一次 GREEN。

实施结果：

- TomlJ 提升为 `agent-bootstrap` 生产依赖，并封装在 Bootstrap 配置包内。
- 实现环境变量/TOML 双模式、配置文件定位、现代/旧字段优先级、Provider 预设和严格原生类型。
- 实现只读 UTF-8 加载、API Key 完整占位符、安全状态、Deferred/未知路径分类和聚合诊断。
- Python 配置 Golden、迁移校验 Golden、文件不改写和脱敏 `toString()` 均由 5 个聚焦测试覆盖。
- C2 有效 RED 为 Resolver 类型缺失；聚焦 GREEN 为 5 Tests 全部通过。审查发现的来源定位和输入脱敏缺陷均补充了方法级 RED/GREEN。

## Task C3：Spring Boot 启动装配（已完成）

- 在 Spring AI 自动配置之前注入已解析模型字段。
- TOML 模式接入 System Prompt 和历史消息数。
- 保持环境变量模式、Java 字符安全上限、模型超时和 Workspace 安全默认。
- 调整 Provider Guard，使其消费统一解析结果且不泄露值。

聚焦验收：Bootstrap 配置集成测试一次 RED、一次 GREEN。

实施结果：

- 通过 Boot 4 `EnvironmentPostProcessor` 在 Config Data 之后、自动配置之前注入 TOML 活动字段。
- TOML 模式接入 Spring AI Base URL、API Key、Model、历史消息数和 System Prompt；Prompt 使用 Base64 内部传递，避免 Spring 二次展开。
- 环境变量模式不增加兼容 PropertySource，继续沿用现有 `application.yml` 和 Provider Guard。
- 无效 TOML 在 Application Context、Workspace 和 SQLite 创建前失败；Post-Processor 通过 `spring.factories` 注册。
- C3 有效 RED 为 Post-Processor、Prompt 接口和注册缺失；聚焦 GREEN 实际执行 9 Tests，全部通过。

## Task C4：无副作用配置检查（已完成）

- 提供配置检查入口，输出字段路径、来源、状态和诊断码。
- 对 Secret、Prompt 和 Plugin 值执行不可绕过的脱敏。
- 证明检查过程不创建 Workspace、SQLite、HTTP Server 或模型客户端。

聚焦验收：配置检查端到端测试一次 RED、一次 GREEN。

实施结果：

- 增加 `--agent.config-check` 启动前检查入口，支持通过 `--agent.config-file` 显式指定配置文件。
- 检查入口复用生产 Resolver，只输出有效性、模式、字段来源、Secret 状态、Deferred/未知路径和稳定诊断码。
- 输出不包含 API Key、System Prompt、Plugin 值、未知字段值或 Base URL 原值。
- 检查在 Spring Application Context 创建前结束，不启动 HTTP Server、模型客户端，也不创建 Workspace 或 SQLite。
- C4 有效 RED 为检查命令类型缺失；聚焦 GREEN 实际执行 3 Tests，全部通过。

## Task C5：运行手册与阶段门禁（已完成）

- 更新 `.env.example`、README 和本地开发运行手册。
- 记录 DeepSeek TOML 启动、环境覆盖、故障排查和回退到环境变量模式的方法。
- 更新 Roadmap、能力矩阵与 Golden 文档索引。

阶段门禁：

```bash
./mvnw spotless:check
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

同时执行 Secret、配置原文件未改写、Workspace/SQLite 副作用和 Git Diff 检查。真实模型 Smoke 不属于本计划的自动门禁。

实施结果：

- 更新 `.env.example`、根 README 和本地开发运行手册，覆盖环境变量/TOML 双模式、DeepSeek、来源优先级、只读检查、诊断和回退。
- 增加可提交的 `config.example.toml`，并忽略根目录真实 `config.toml`，避免误提交本地配置和密钥。
- 更新文档导航、Roadmap、能力矩阵和 Java 重写指南，使其反映配置兼容已经落地，下一步转为消息、生命周期和 Tool Contract。
- `./mvnw spotless:check`、`./mvnw clean verify`、`./mvnw -Pfailure verify` 和最终 `./mvnw -Pcompat verify` 全部通过。
- 首次 `compat` 门禁暴露注册测试错误地实例化全部 Spring Factory；改为直接验证 `spring.factories` 后，3 个聚焦 Tests 和完整 `compat` 均通过，且消除了 Deprecated API 编译告警。
- Git Diff、敏感信息模式、Golden 未改动、禁止跟踪的真实配置/Workspace/SQLite/日志产物检查均通过；配置原文件不改写和配置检查无副作用由自动化测试覆盖。
- 未运行真实模型 Smoke，不访问真实模型，不产生模型费用。
