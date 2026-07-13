# Python/Java 配置兼容实施计划

- 状态：待实施
- 日期：2026-07-13
- Spec：[Python/Java 配置兼容设计](../specs/2026-07-13-python-java-configuration-compatibility-design.md)
- Contract：[Python/Java 配置兼容契约](../contracts/python-java-configuration.md)

本计划只安排后续实现；本次契约设计不执行这些任务。

## Task C1：配置 Golden 与 Parser 选型

- 为 Python Golden 生成器增加配置共同投影。
- 固化现代 DeepSeek、根级旧字段、优先级、Provider 预设、环境变量和 Deferred Case。
- 调研并确定 Java TOML Parser 的版本、许可证和维护状态。
- 在 `agent-bootstrap` 增加测试范围依赖和夹具读取，不新增 Maven 模块。

聚焦验收：Python 生成器重复运行字节一致；Manifest Hash 更新经过审查。

## Task C2：只读文档与 Resolver

- 先用测试固定文件定位、双模式和来源优先级。
- 实现只读 `ConfigurationDocument`、类型化活动快照和结构化诊断。
- 实现现代/旧字段、Provider 预设、严格类型和 API Key 占位符规则。
- 确认未知/Deferred 字段不会失败，原配置文件字节不变。

聚焦验收：只运行 `agent-bootstrap` 配置 Resolver 测试一次 RED、一次 GREEN。

## Task C3：Spring Boot 启动装配

- 在 Spring AI 自动配置之前注入已解析模型字段。
- TOML 模式接入 System Prompt 和历史消息数。
- 保持环境变量模式、Java 字符安全上限、模型超时和 Workspace 安全默认。
- 调整 Provider Guard，使其消费统一解析结果且不泄露值。

聚焦验收：Bootstrap 配置集成测试一次 RED、一次 GREEN。

## Task C4：无副作用配置检查

- 提供配置检查入口，输出字段路径、来源、状态和诊断码。
- 对 Secret、Prompt 和 Plugin 值执行不可绕过的脱敏。
- 证明检查过程不创建 Workspace、SQLite、HTTP Server 或模型客户端。

聚焦验收：配置检查端到端测试一次 RED、一次 GREEN。

## Task C5：运行手册与阶段门禁

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
