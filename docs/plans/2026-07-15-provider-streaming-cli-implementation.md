# Provider Streaming 与本地 CLI 工作计划

- 状态：实施中
- 日期：2026-07-15
- 阶段：R6.2
- 当前执行状态：Task D0–D4 已完成；下一步 Task D5 OpenAI-compatible SSE 集成
- 基线：R6.1 已通过 PR #4 三套远程 CI，并以 `a77b088` 合入 `main`
- 批准记录：用户已批准并要求完整实现 R6 总体计划
- Contract：[Provider Streaming 与本地 CLI 契约](../contracts/provider-streaming-cli.md)
- Spec：[Provider Streaming 与本地 CLI 设计](../specs/2026-07-15-provider-streaming-cli-design.md)
- ADR：[ADR-0008：使用项目自有同步流式观察协议桥接 Provider](../adr/0008-use-project-owned-synchronous-stream-observer.md)

## 1. 目标

用可独立提交的 TDD Task 完成真实 Provider 文本 Chunk 到 Application、R6.1 Message Runtime 和本地 CLI 的纵向闭环，同时保持现有 HTTP、Tool/MCP、Memory 和 SQLite 语义。

## 2. Task 顺序

### Task D0：Contract、ADR、Spec 与计划

状态：已完成。

新增：

- `docs/contracts/provider-streaming-cli.md`
- `docs/adr/0008-use-project-owned-synchronous-stream-observer.md`
- `docs/specs/2026-07-15-provider-streaming-cli-design.md`
- `docs/plans/2026-07-15-provider-streaming-cli-implementation.md`

更新文档导航、ADR 导航和 R6 总体状态。纯文档任务执行：

```bash
./mvnw -N spotless:check
```

验证证据（2026-07-15）：R6.1 已先通过 PR #4 三套远程 CI 并合入 `main`；从 `a77b088` 创建独立 R6.2 分支。完成 Contract、ADR、Spec、计划和文档导航后，`./mvnw -N spotless:check` 退出码为 `0`。

### Task D1：Java-owned Fixture 与 Kernel 流式协议

状态：已完成。

新增：

- `testdata/golden/message-bus/provider-streaming-cli.json`
- Kernel `CancellationSignal`、`ChatModelStreamObserver`。
- `ChatModelPort` 兼容流式重载。
- Kernel Golden/Contract 测试。

RED/GREEN：

```bash
./mvnw -pl agent-kernel -Dtest=ProviderStreamingContractTest test
```

Fixture 分为 Kernel、Application 和 CLI 三组。D1 由生产 Kernel 类型实际消费全部 Kernel Case；后续 D2/D3 和 D6 分别消费 Application/CLI Case，D8 验证三组都已有生产实现覆盖。

验证证据（2026-07-15）：聚焦命令先因 `CancellationSignal`、`ChatModelStreamObserver` 和 `ChatModelPort` 流式重载缺失而在测试编译阶段失败；实现纯 JDK 协议和兼容默认重载后，同一命令执行 2 个测试并全部通过，逐项消费 6 个 Kernel Fixture Case。默认重载不制造伪 Delta，预取消零 Provider 调用，原单抽象方法和 Lambda 兼容性保留；Kernel Spotless 检查退出码为 `0`。

### Task D2：Application Streaming Budget 与 Tool Loop

状态：已完成。

新增/修改：

- `ChatProgressListener`、`StreamingBudget`、`ModelStreamLimitExceededException`。
- `TurnCancellation extends CancellationSignal`。
- `ToolLoop`、`ChatService`、`ChatUseCase` 的兼容流式重载。

RED/GREEN：

```bash
./mvnw -pl agent-application -am -Dtest=StreamingToolLoopTest -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖多模型迭代、Tool Call Fragment 不可见、最终文本、事件/字符预算、预取消、运行中取消和成功提交。

验证证据（2026-07-15）：聚焦命令先因三参数流式入口、`ModelStreamingSettings`、`StreamingBudget` 和稳定流上限异常缺失而在测试编译阶段失败；实现后同一命令执行 4 个测试并全部通过。测试消费 5 个预算 Fixture Case，确认 Delta 事件与 Unicode Code Point 上限均为包含式边界，跨 Tool Loop 迭代共用一个 Turn 预算，取消后不再公开迟到 Delta，且 SQLite 只提交最后一次无 Tool Call 响应的权威文本。Application 全量回归连同 Kernel 共执行 169 个测试并全部通过。

### Task D3：Message Turn Delta 投影

状态：已完成。

修改 `MessageTurnService` 与测试，使其产生 Started、Delta* 和一个终态。验证 Delta Sink 故障、背压、断开、取消/完成竞争和终态失败不二次发布。

RED/GREEN：

```bash
./mvnw -pl agent-application -am -Dtest=MessageTurnStreamingTest -Dsurefire.failIfNoSpecifiedTests=false test
```

验证证据（2026-07-15）：聚焦命令先执行 6 个测试并全部因 `MessageTurnService` 仍调用旧非流式 Chat 入口而失败；改为通过同一个 `OutboundMessageSequence` 发布 Delta，并把流预算异常映射为稳定 `TURN_LIMIT_EXCEEDED` 后，同一命令 6 个测试全部通过。测试消费剩余 4 个 Application Fixture Case，覆盖严格序号、空白 Delta、Tool 迭代暂定预览、权威完成快照、Delta 后取消、Sink 断开原样传播及不二次发布终态。Application 全量回归连同 Kernel 共执行 175 个测试并全部通过。

### Task D4：Spring AI Streaming Adapter

状态：已完成。

修改 Adapter、配置与观察包装，增加 Fake Flux 单元测试。覆盖 Options 保留、文本 Chunk、Tool Call 聚合、空闲超时、取消、Observer 异常、损坏响应和迟到事件。

RED/GREEN：

```bash
./mvnw -pl adapter-spring-ai -am -Dtest=SpringAiStreamingChatModelAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

验证证据（2026-07-15）：聚焦命令先因流式构造和桥接缺失而在测试编译阶段失败；实现同步 Observer、单项需求、空闲超时、First Terminal Wins 和取消释放后，聚焦测试转为绿色。自审发现直接 Adapter 文本与 Tool Call 聚合仍可无界，新增 2 个测试先失败，再加入 32,000 Code Point 与 128 Tool Call Adapter 硬上限；最终同一测试类 10 个测试全部通过。另以独立 RED/GREEN 确认 `ObservedChatModelPort` 和 `SafeChatUseCase` 不会把流式重载降级为同步调用。Bootstrap 全量回归曾暴露轻量 Spring Context 无法转换 `@Value Duration`，改为严格显式解析后，`MemoryConfigurationTest` 8 个测试和最终 `agent-bootstrap -am test` 共 331 个测试全部通过；Adapter 与 Bootstrap Spotless 均通过。

### Task D5：OpenAI-compatible SSE 集成

状态：待开始。

扩展现有 HTTP Stub Integration，验证真实 SSE 请求、Chunk、Tool Schema、Assistant Tool Call、Tool Result 回送、最终文本和取消连接关闭。该测试使用本地 Stub，不访问真实 Provider。

聚焦验收：

```bash
./mvnw -pl adapter-spring-ai -am -Dit.test=OpenAiCompatibleStreamingAdapterIT -DskipUnitTests verify
```

### Task D6：纯本地 CLI Runner

状态：待开始。

新增 `CliProperties`、输入/输出边界、`LocalCliRunner` 和可控 Thread Starter。测试普通回答、多 Delta、权威快照纠正、稳定错误、EOF、输出故障、启动失败和 Shutdown。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am -Dtest=LocalCliRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task D7：CLI Bootstrap 与配置

状态：待开始。

修改 Application 启动路径、YAML、模板和 Runbook。验证显式 CLI 使用 Non-Web Context，普通启动与配置检查不变，默认无真实渠道网络。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am -Dtest=CliBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task D8：故障、兼容和提交验收

状态：待开始。

把 Idle Timeout、损坏流、取消/完成、背压/断开、输出故障、Tool/MCP 取消和 SQLite 半轮次隔离加入 `failure`/`compat`。Fixture-only Task 不人为破坏生产实现。

### Task D9：阶段门禁与文档收口

状态：待开始。

执行：

```bash
./mvnw spotless:check
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
./mvnw -pl agent-kernel dependency:tree
```

另执行 Secret、敏感文件、Kernel 禁止依赖、无界队列、后台任务、Subscription、进程和工作树检查。记录实际测试数，更新状态为“已实现并验证”。

## 3. 提交策略

- D0：文档与决策。
- D1：Kernel 流式协议与 Fixture。
- D2：Application Tool Loop。
- D3：Channel Delta 投影。
- D4：Spring AI 单元 Adapter。
- D5：SSE 集成。
- D6：CLI Runner。
- D7：CLI Bootstrap/配置。
- D8：验收资产。
- D9：阶段门禁文档。

每个 Task 保留一次有效 RED/GREEN或规定的验收命令，并在聚焦 GREEN、`git diff --check` 和自审后独立提交。

## 4. 暂停条件

- 需要真实 Provider、Key、网络或付费 Smoke。
- 需要新增 Maven 模块、数据库表、消息中间件或真实渠道。
- 需要把 Reactor/Spring AI 引入 Kernel/Application。
- 需要展示 Thinking、Tool Arguments/Result、Memory 正文或原始异常。
- 需要修改同步 HTTP 外部契约或现有 SQLite 成功提交语义。
