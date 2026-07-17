# 分阶段测试与验证节奏设计

> 状态：已实现并验证；2026-07-17 增补互斥测试集
>
> 日期：2026-07-13
>
> 适用范围：Java Agent 全部后续里程碑

## 1. 目的

在不降低行为验证可信度的前提下，减少每个小任务重复运行完整 Maven Reactor、格式化、兼容性 Profile、故障注入 Profile 和稳定性循环造成的等待时间。

本设计只调整测试执行节奏，不降低被动聊天 MVP 的最终验收标准，也不改变生产架构、接口、失败语义或兼容性要求。

2026-07-17 的增补进一步消除 Profile 之间的重复执行，不删除已有契约测试，也不改变真实外部资源的授权边界。

## 2. 核心规则

每个小任务只执行一次有效的聚焦 RED 和一次聚焦 GREEN：

1. 先为本任务的全部新增行为编写聚焦测试。
2. 运行一次目标测试命令，确认它因为缺少或错误的目标行为而失败。编译错误、测试失败或契约失败均可作为 RED，但测试选择器、依赖解析和命令拼写错误不能作为有效 RED。
3. 完成本任务的最小生产实现。
4. 再运行一次相同的目标测试命令，确认目标模块实际执行了规定测试且全部通过。
5. 小任务不再运行 `./mvnw clean verify`、`./mvnw spotless:apply`、`./mvnw -Pfailure verify`、`./mvnw -Pcompat verify` 或重复稳定性循环。

当一个任务包含多个紧密相关行为时，允许把它们合并到同一个 RED/GREEN 周期中。不得为了减少命令次数而省略关键断言、把零测试当成功，或使用真实模型、真实用户 Workspace 和外部密钥。

### 2.1 互斥测试集

| 测试集 | 选择规则 | 用途 |
| --- | --- | --- |
| 默认 | 排除 `compat`、`failure`、`real-model` | 快速常规回归 |
| `failure` | 只包含 `@Tag("failure")` | 故障、竞态和资源释放 |
| `compat` | 只包含 `@Tag("compat")` | Golden、Schema 和版本契约 |
| `real-model-smoke` | 只包含 `@Tag("real-model")` | 经单独授权的真实模型验收 |

四个集合不互相隐式包含。最终门禁通过分别运行默认、`failure` 和 `compat` 获得完整离线证据；
真实模型仍不属于默认 CI。聚焦测试通过精确 `-Dtest`/`-Dit.test` 选择类；若同一 Task 同时包含
常规和 `failure` 方法，可增加 `-Dexcluded.test.groups=compat,real-model`，但不得移除精确选择器。

测试代码遵循以下约束：

- 相同生产路径的输入矩阵使用参数化 Case，不复制测试方法和 Fixture。
- 单元层证明状态机，集成层只补 Wiring、事务、并发或安全边界，不重复所有字段断言。
- 使用可控 Clock、Latch、Barrier 和 Fake，不用 `sleep` 或重复循环换取稳定性。
- 不删除取消 First Writer、SQLite 提交边界、Observer 隔离、背压和安全拒绝等高风险契约。

只新增兼容性、端到端或验收测试而不修改生产行为的任务，不人为破坏正确实现来制造 RED；此类任务只执行一次聚焦验收命令，完整 Profile 延后到阶段门禁。

## 3. 审查与修复

- 每个任务完成一次 Git diff、Spec、模块边界和异常路径自审；不为自审重复运行已经通过的测试。
- 自审或阶段整体审查发现 Critical 或 Important 行为缺陷时，为缺陷增加一个聚焦失败测试，完成修复后运行一次对应 GREEN。该修复周期属于缺陷处理，不要求提前运行全量构建。
- 纯文档或构建命令修正只运行与其直接相关的一条验证命令，不触发完整 Reactor。
- Minor 记录在实施计划的“未解决事项”中，由阶段审查或最终整分支审查统一裁决。
- 只有出现真实失败或疑似不稳定时才允许重复运行聚焦测试；重复次数和原因必须在任务提交说明或实施计划中解释。

## 4. 阶段划分与统一验证

### 阶段 A：核心领域与应用层（Task 1–4）

验收范围：Maven 骨架、消息模型、历史窗口、聊天用例、同会话串行闸门。

该阶段已经完成普通 Reactor 和 `failure` Profile 验证。Task 4 复审通过后不再重复运行阶段测试。

### 阶段 B：SQLite 持久化（Task 5–6）

完成 Task 6 及其审查后统一运行：

```bash
./mvnw spotless:apply
./mvnw -pl adapter-sqlite -am verify
./mvnw clean verify
./mvnw -Pfailure verify
```

必须覆盖 Schema 初始化与拒绝不兼容 Schema、真实临时 SQLite、完整轮次原子写入、事务失败零部分写入和故障注入测试。

### 阶段 C：模型、装配与 HTTP（Task 7–10）

完成 Task 10 及其审查后统一运行：

```bash
./mvnw spotless:apply
./mvnw -pl adapter-spring-ai -am verify
./mvnw -pl agent-bootstrap -am verify
./mvnw clean verify
```

必须使用本地 OpenAI-compatible 桩服务，禁止访问真实模型；覆盖消息角色映射、Provider 异常、配置装配、HTTP Contract、请求 ID 和错误映射。

### 阶段 D：安全、兼容与最终验收（Task 11–13）

完成 Task 13 及整分支审查前统一运行：

```bash
./mvnw spotless:apply
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

同时执行实施计划规定的架构依赖、Secret、Workspace、健康检查、日志脱敏和端到端验收命令。

## 5. 阶段失败处理

阶段验证失败时：

1. 使用系统化调试流程确认根因，不直接批量修改。
2. 只运行能复现失败的最小命令，直到修复通过。
3. 所有聚焦修复完成后，重新运行一次完整阶段验证。
4. 阶段验证未全部通过时，不进入下一阶段，也不得声称该阶段完成。

## 6. 证据与台账

- 每个任务在提交说明中记录一次有效 RED、一次 GREEN 和自审结论，不创建独立任务报告。
- 每个阶段在实施计划中记录验证命令、测试数、失败数、Profile 选择结果和未解决事项，不创建独立执行台账。
- 测试数按互斥集合分别记录；2026-07-17 前的累计 Profile 数保留为历史证据，不回写也不与新口径直接比较。
- 实施计划的“当前执行状态”和 Git 提交是恢复执行位置的依据；对话摘要不能替代仓库内状态。
- 最终“已实现并验证”结论必须以阶段 D 和整分支审查证据为准，不能由单个小任务的 GREEN 代替。

## 7. 需要同步的治理文件

本设计书面复核后，需要同步修改：

- `AGENTS.md`：把“每任务完整构建”改为“小任务聚焦 RED/GREEN、阶段完整验证”。
- `docs/plans/2026-07-13-passive-chat-mvp-implementation.md`：删除 Task 5–13 中重复的每任务全量命令，在 Task 6、Task 10 和 Task 13 增加对应阶段门禁。
- `docs/vibe-coding-workflow.md`：定义单代理连续执行、自审和阶段集中验证流程。

Task 1–4 已产生的验证证据继续有效，不重写历史提交。

## 8. 互斥测试集验证证据

2026-07-17 在 R6.5 G4 工作树上完成测试选择与全量验证：

- 默认 `clean verify` 执行 461 项常规测试，0 失败、0 错误、0 跳过，用时 31.438 秒。
- `-Pfailure verify` 只执行 138 项故障/竞态测试，全部通过，用时 37.283 秒。
- `-Pcompat verify` 只执行 106 项 Golden/Schema 测试，全部通过，用时 12.011 秒。
- 三组共 705 项且互不重复；旧选择规则会在三条门禁中累计执行 1,442 项，本次减少 737 次重复执行，约 51.1%。
- G4 精确选择器在一次 Maven 调用中执行 Application 10 项、Bootstrap 16 项，共 26 项，全部通过，用时 4.181 秒。
- `real-model-smoke` 只通过 Effective POM 验证选择 `real-model` 并排除 `compat,failure`，未读取 Key、未访问网络、未产生费用。

本次没有删除测试源码；控制面最慢测试类低于 1 秒，故保留 Claim、取消、Observer、并发和 Durable Commit 边界。故障 Profile 的主要本地耗时来自 MCP 子进程故障矩阵，后续若继续优化应针对进程夹具复用做独立设计，不能通过删减协议场景处理。
