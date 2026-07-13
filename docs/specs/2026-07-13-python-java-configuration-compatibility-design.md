# Python/Java 配置兼容设计

- 状态：已批准
- 日期：2026-07-13
- Contract：[Python/Java 配置兼容契约](../contracts/python-java-configuration.md)
- 关联 Roadmap：R0 治理与基线、R2 被动聊天纵向切片
- Python 参考 Commit：`b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`

## 1. 目标

为 Java 增加一个只读配置兼容边界，使现有 Python `config.toml` 可以驱动已经迁移的被动聊天字段，同时继续接受尚未迁移和未知配置。现有纯环境变量启动必须保持可用。

## 2. 非目标

- 本设计不实现配置加载代码。
- 不激活 Tool、Memory、Proactive、渠道、插件、MCP 或多模型策略。
- 不写回、格式化或自动迁移 TOML。
- 不切换到 Python 默认工作区。
- 不提供热更新、远程配置中心或 Secret Manager。
- 不新增第六个 Maven 模块。

## 3. 现状证据

Python：

- `agent/config.py` 使用 `tomllib` 读取 `.toml`。
- 现代 `[llm]`、`[llm.main]`、`[agent]` 字段优先，根级旧字段回退。
- DeepSeek、OpenAI、Qwen 存在 Base URL 预设。
- 主、Fast、Agent、VL、Memory Embedding API Key 支持 `${ENV_NAME}`。
- Plugin 配置递归展开环境变量，并兼容 `[channels.qqbot]` 到 `[plugins.qqbot]`。
- 未投影到 `Config` 的普通字段会被忽略；Proactive 子树有额外严格校验。
- 默认配置路径为 `./config.toml`，默认工作区为 `~/.akashic/workspace`。

Java：

- `application.yml` 通过 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL` 配置 Spring AI。
- `AgentProperties` 当前只包含 Workspace、历史限制和模型/会话超时。
- System Prompt 来自 Classpath 资源。
- Provider Guard 对 Base URL、API Key 和 Model 执行启动校验。
- 默认 Workspace 是 `./workspace`，现有运行手册要求与 Python 可写工作区隔离。

## 4. 设计结构

兼容实现保留在 `agent-bootstrap`，不污染核心模块：

```text
config.toml + process environment
              |
              v
ConfigurationDocument（只读原始树）
              |
              v
ConfigurationResolver（优先级、别名、预设、诊断）
              |
              +--> ActiveConfigurationSnapshot
              |       ├── provider/model/baseUrl/secret
              |       ├── systemPrompt
              |       └── historyMaxMessages
              |
              +--> ConfigurationReport（仅字段路径、来源、状态）
              |
              v
Spring Boot/Spring AI 装配
```

`ConfigurationDocument` 和 TOML 解析器类型只能存在于 Bootstrap 配置包。`agent-kernel`、`agent-application` 和适配器公开接口不得依赖 TOML 或 Spring `Environment` 类型。

## 5. 启动集成

配置解析必须发生在 Spring AI 自动配置读取模型属性之前。实现可以使用 Spring Boot 的早期 Environment 扩展点，但最终只注入已经解析和校验的活动字段。

来源优先级由项目 Resolver 明确实现，不能依赖不同操作系统对环境变量名称的模糊转换。环境变量模式不创建空的 TOML 文档。

System Prompt 装配调整为：

1. TOML 兼容模式使用解析后的 Prompt。
2. 环境变量模式继续读取 Classpath Prompt。

历史消息数从兼容快照进入现有 `HistoryLimits`；字符安全上限保持 Java 当前默认。

## 6. 数据与安全

- Secret 使用专用值对象或受限字段传递，禁止出现在 `toString()`、Record 默认输出和配置报告。
- 配置报告不得包含原值、配置正文、Prompt、插件配置值或 Secret Hash。
- 配置检查模式不得初始化 Spring AI、SQLite、Workspace 或 HTTP Server。
- TOML 原始树不得跨越 Bootstrap 边界，也不得被缓存到全局可变状态。
- 第一阶段禁止任何配置写入 API。

## 7. 失败语义

解析和校验产生结构化诊断列表；启动层只负责把列表转换为一次失败。多个非敏感问题应一次报告，减少用户反复启动排错。

显式配置文件错误、TOML 语法错误和活动字段错误必须 Fail Fast。Deferred 或未知字段不得导致失败。

配置检查命令与正常启动必须复用同一个 Resolver，禁止形成两套优先级或校验规则。

## 8. 测试策略

- Python 生成器固化配置解析共同投影，不包含真实密钥。
- Java `compat` 测试读取配置 Golden，比较来源、规范化活动值和诊断码。
- Java 单元测试补齐文件定位、环境变量优先级、严格类型、Provider 预设和脱敏。
- Bootstrap 集成测试证明 TOML 值在 Spring AI 自动配置前生效。
- 配置检查测试证明不会创建 Workspace 或 SQLite。
- 失败 Profile 覆盖缺文件、非法 TOML、未展开变量、非法 URL 和多问题聚合。

## 9. 依赖约束

已通过 [ADR-0004](../adr/0004-use-tomlj-for-read-only-toml-parsing.md) 选择 `org.tomlj:tomlj:1.1.1`。该版本声明支持 TOML 1.0，采用 Apache-2.0 许可证，并已通过项目 JDK 21 配置夹具测试。其维护节奏较低是已记录风险；Parser 依赖只能进入 `agent-bootstrap`。

不得为了配置兼容引入 Spring Cloud Config、数据库配置表、消息中间件或新的部署单元。

## 10. 验收标准

- 同一 Resolver 同时服务正常启动和无副作用配置检查。
- 环境变量模式保持现有行为。
- Contract 中六个活动字段按优先级解析。
- Deferred 和未知字段被接受且原文件不变。
- DeepSeek 配置无需修改为 OpenAI Provider 即可工作。
- 缺失 Secret、非法类型和非法 URL 使用稳定诊断码失败。
- 默认 Java Workspace 不因 TOML 模式变为 Python Workspace。
- Python/Java 配置 Golden、默认、`failure`、`compat` 门禁全部通过。
