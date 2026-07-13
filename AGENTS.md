# AGENTS.md

## 项目使命

使用 Java 重写 Akashic Agent，同时保持可观察行为、工作区兼容性和现有用户数据不变。

当前以 Python 实现作为行为基准。本项目采用渐进式重写，不进行一次性整体替换。

## 固定技术基线

- 使用 JDK 21。
- 禁止使用预览 API 或 `--enable-preview`。
- 使用 Maven Wrapper 构建：`./mvnw`。
- 使用 Spring Boot 4.1.x。
- Spring AI 2.0.x 只作为集成适配器使用。
- 智能体循环、工具执行、审批流程、上下文管理和会话生命周期由本项目自行控制。

## 架构边界

- `agent-kernel` 不得依赖 Spring、JDBC、Reactor、Spring AI 或模型供应商 SDK 类型。
- 领域接口属于核心模块。
- 外部框架和 SDK 属于适配器模块。
- Spring AI 不得成为智能体运行时的控制者。
- SQLite 访问使用显式 SQL 和事务。
- 未经批准的 ADR，不得引入 JPA、R2DBC、WebFlux、Kafka、Redis、GraalVM 或分布式基础设施。
- 初始重写阶段保持现有 React/Vite 前端不变，除非规格明确要求修改。

## 强制开发流程

实现新行为前必须：

1. 使用 `using-superpowers` 选择适用的工作流。
2. 使用 `brainstorming` 澄清行为和约束。
3. 编写并批准规格（Spec）。
4. 使用 `writing-plans` 生成可执行的实施计划。
5. 创建隔离的功能分支或 Git Worktree。
6. 使用 TDD 实现。
7. 请求独立代码审查。
8. 在声称完成前执行全新的验证。
9. 集成前使用 `finishing-a-development-branch`。

规格和实施计划存在之前，不得编写生产代码。

修复缺陷时必须：

1. 使用失败测试复现问题。
2. 使用 `systematic-debugging` 定位根因。
3. 实施有依据的最小修复。
4. 执行回归测试和完整验证。

## 测试驱动开发

遵循 Red-Green-Refactor：

1. 为一个可观察行为添加测试。
2. 运行测试，并确认测试因预期原因失败。
3. 编写使其通过所需的最少代码。
4. 重新运行并确认测试通过。
5. 仅在测试保持绿色时重构。

实现完成后才补写的测试不能作为 TDD 证据。

## 构建与验证

主要命令：

```bash
./mvnw clean verify
./mvnw -pl <module> -am test
./mvnw -Pcompat verify
./mvnw -Pfailure verify
```

声称完成前必须：

- 运行与变更相关的目标测试。
- 运行 `./mvnw clean verify`。
- 可观察行为发生变化时运行兼容性测试。
- 持久化、工具、审批、取消或并发行为发生变化时运行故障注入测试。
- 报告执行过的准确命令和结果。
- 禁止根据旧的或不完整的测试输出声称成功。

## 兼容性与数据安全

- 在相应 Java 行为通过验收前，以 Python 实现作为行为基准。
- 禁止 Python 和 Java 同时写入同一个工作区。
- 初始阶段只能以只读方式访问真实用户工作区。
- 第一次由 Java 写入或迁移 Schema 前必须备份数据。
- 尽可能保留未知的 JSON 和 TOML 字段。
- 禁止静默重写 Markdown 记忆文件。
- 任何有意修改 Golden File 或契约的行为都必须经过审查。

禁止提交：

- 真实的 `config.toml` 文件
- API Key 或访问令牌
- 生产 SQLite 数据库
- 用户工作区
- 用户记忆
- 含有隐私数据的运行日志

## Git 规则

- 禁止直接在 `main` 上实现功能。
- 使用短生命周期的功能分支或 Worktree。
- 保留用户已有变更。
- 未经明确授权，禁止执行破坏性 Git 命令。
- 提交应保持小型且可独立验证。
- 每个提交优先只包含一个可观察行为。
- 禁止把无关重构混入行为迁移。

## 完成定义

任务仅在满足以下全部条件时才算完成：

- 规格已批准。
- 实施计划已经执行，或其变更已经更新到文档。
- 存在 Red-Green 测试证据。
- 目标测试通过。
- `./mvnw clean verify` 通过。
- 兼容性差异已有解释。
- 审查不存在未解决的 Critical 或 Important 问题。
- 相关文档和 ADR 已更新。
- 未加入密钥或用户数据。
- Git 状态中不存在非预期变更。

## 文档语言

- 项目文档默认使用中文。
- 专业术语、类名、接口名、模块名、配置键、命令、协议名和代码等确有必要的内容保留英文。
- 文档标题、正文、约束和验收标准均应使用中文；Git 提交信息的语言按具体任务约定执行。
- 新增或修改文档时，应保持同一术语的中英文写法一致。

## 文档导航

实现前阅读与任务相关的文档：

- `docs/architecture/java-rewrite-guide.md`
- `docs/adr/`
- `docs/contracts/`
- `docs/superpowers/specs/`
- `docs/superpowers/plans/`
- `docs/runbooks/`

详细设计决策应写入上述文档，不应堆积在本文件中。
