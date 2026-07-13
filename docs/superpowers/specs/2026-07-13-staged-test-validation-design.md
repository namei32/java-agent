# 分阶段测试与验证节奏设计

> 状态：待书面复核  
> 日期：2026-07-13  
> 适用范围：被动聊天 MVP 实施计划 Task 1–13

## 1. 目的

在不降低行为验证可信度的前提下，减少每个小任务重复运行完整 Maven Reactor、格式化、兼容性 Profile、故障注入 Profile 和稳定性循环造成的等待时间。

本设计只调整测试执行节奏，不降低被动聊天 MVP 的最终验收标准，也不改变生产架构、接口、失败语义或兼容性要求。

## 2. 核心规则

每个小任务只执行一次有效的聚焦 RED 和一次聚焦 GREEN：

1. 先为本任务的全部新增行为编写聚焦测试。
2. 运行一次目标测试命令，确认它因为缺少或错误的目标行为而失败。编译错误、测试失败或契约失败均可作为 RED，但测试选择器、依赖解析和命令拼写错误不能作为有效 RED。
3. 完成本任务的最小生产实现。
4. 再运行一次相同的目标测试命令，确认目标模块实际执行了规定测试且全部通过。
5. 小任务不再运行 `./mvnw clean verify`、`./mvnw spotless:apply`、`./mvnw -Pfailure verify`、`./mvnw -Pcompat verify` 或重复稳定性循环。

当一个任务包含多个紧密相关行为时，允许把它们合并到同一个 RED/GREEN 周期中。不得为了减少命令次数而省略关键断言、把零测试当成功，或使用真实模型、真实用户 Workspace 和外部密钥。

## 3. 审查与修复

- 每个任务仍保留一次只读 Spec/质量审查；审查者不重复运行实现者已经执行的测试。
- 审查发现 Critical 或 Important 行为缺陷时，修复者为缺陷增加一个聚焦失败测试，完成修复后运行一次对应 GREEN。该修复周期属于缺陷处理，不要求提前运行全量构建。
- 纯文档或构建命令修正只运行与其直接相关的一条验证命令，不触发完整 Reactor。
- Minor 进入执行台账，由阶段审查或最终整分支审查统一裁决。
- 只有出现真实失败或疑似不稳定时才允许重复运行聚焦测试；重复次数和原因必须写入报告。

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

1. 使用 `systematic-debugging` 确认根因，不直接批量修改。
2. 只运行能复现失败的最小命令，直到修复通过。
3. 所有聚焦修复完成后，重新运行一次完整阶段验证。
4. 阶段验证未全部通过时，不进入下一阶段，也不得声称该阶段完成。

## 6. 证据与台账

- 每个任务报告只记录该任务的一次有效 RED、一次 GREEN、提交和审查结论。
- 每个阶段单独记录阶段验证命令、测试数、失败数、Profile 选择结果和未解决 Minor。
- `.superpowers/sdd/progress.md` 是恢复执行位置的唯一台账；对话摘要不能替代 Git 提交和台账记录。
- 最终“已实现并验证”结论必须以阶段 D 和整分支审查证据为准，不能由单个小任务的 GREEN 代替。

## 7. 需要同步的治理文件

本设计书面复核后，需要同步修改：

- `AGENTS.md`：把“每任务完整构建”改为“小任务聚焦 RED/GREEN、阶段完整验证”。
- `docs/superpowers/plans/2026-07-13-passive-chat-mvp-implementation.md`：删除 Task 5–13 中重复的每任务全量命令，在 Task 6、Task 10 和 Task 13 增加对应阶段门禁。
- Subagent 派发模板上下文：实现者只运行任务聚焦测试；阶段控制器负责统一格式化和完整验证。

Task 1–4 已产生的验证证据继续有效，不重写历史提交。
