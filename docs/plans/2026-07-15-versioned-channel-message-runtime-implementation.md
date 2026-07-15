# 版本化渠道消息 Contract Runtime 工作计划

- 状态：实施中
- 日期：2026-07-15
- 阶段：R6.1
- 当前执行状态：Task C0–C3 已完成；下一步 Task C4
- 基线：MCP R5.1 已通过 PR #3 的默认、`failure`、`compat` 远程 CI，并以 `d766bb8` 合入 `main`
- 批准记录：用户要求完成 MCP PR 与远程 CI，然后从 R6 的版本化 Message Contract Fixture 开始连续 TDD 实现
- Contract：[版本化渠道消息与流式运行时契约](../contracts/versioned-channel-message-runtime.md)
- Spec：[版本化渠道消息 Contract Runtime 设计](../specs/2026-07-15-versioned-channel-message-runtime-design.md)
- ADR：[ADR-0007：采用项目自有的有界渠道消息协议](../adr/0007-use-project-owned-bounded-channel-message-protocol.md)

## 1. 目标

用一组可独立提交、可聚焦验证的 TDD Task 完成 R6.1：版本化 Java-owned Fixture、Kernel 消息模型与序号状态机、取消原因、有界背压和现有 Chat 的安全 Channel 投影。

本计划完成后仍不启用真实流式、CLI 或真实渠道。下一计划 R6.2 从本 Contract 接入本地 CLI 和 Provider Streaming，不重新定义消息格式。

## 2. Task 顺序

### Task C0：Contract、Spec、ADR 与计划

状态：已完成。

新增：

- `docs/contracts/versioned-channel-message-runtime.md`
- `docs/adr/0007-use-project-owned-bounded-channel-message-protocol.md`
- `docs/specs/2026-07-15-versioned-channel-message-runtime-design.md`
- `docs/plans/2026-07-15-versioned-channel-message-runtime-implementation.md`

工作：固定范围、字段、终态、取消、背压、错误和非目标；更新文档导航。纯文档任务不制造 RED。

验证：`./mvnw -N spotless:check`

### Task C1：Java-owned Fixture 与 Kernel 消息值

状态：已完成。

新增：

- `testdata/golden/message-bus/versioned-channel-message.json`
- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/MessageContract.java`
- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/MessageRoute.java`
- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/InboundMessage.java`
- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/OutboundMessage.java`
- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/OutboundMessageType.java`
- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/TurnCancellationCode.java`
- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/TurnFailureCode.java`
- `agent-kernel/src/test/java/io/namei/agent/kernel/channel/MessageContractGoldenTest.java`

修改：`testdata/golden/manifest.json`

RED/GREEN：

```bash
./mvnw -pl agent-kernel -Dtest=MessageContractGoldenTest test
```

RED 必须因生产消息类型缺失或不满足 Fixture 行为失败；GREEN 必须实际执行全部 Fixture Case。

验证证据（2026-07-15）：聚焦命令先因 `InboundMessage`、`MessageRoute`、`OutboundMessage` 和 `OutboundMessageType` 缺失而编译失败；实现纯 JDK 生产类型后，同一命令执行 1 个 Fixture 测试并逐项消费 40 个 Case。自审发现 `MessageRoute.toString()` 暴露原始 Channel，补充聚焦测试后先稳定失败，再改为全字段脱敏；同一命令最终执行 2 个测试并全部通过。

### Task C2：严格序号和唯一终态

状态：已完成。

新增：

- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/OutboundMessageSequence.java`
- `agent-kernel/src/main/java/io/namei/agent/kernel/channel/OutboundSequenceValidator.java`
- `agent-kernel/src/test/java/io/namei/agent/kernel/channel/OutboundMessageSequenceTest.java`

RED/GREEN：

```bash
./mvnw -pl agent-kernel -Dtest=OutboundMessageSequenceTest test
```

覆盖无 Delta 完成、多 Delta、缺口/重复/乱序、终态后事件和并发终态竞争。

验证证据（2026-07-15）：聚焦命令先因 `OutboundMessageSequence` 和 `OutboundSequenceValidator` 缺失而编译失败；实现后同一命令执行 6 个测试并全部通过。确定性双线程闩锁证明 `COMPLETED/CANCELLED` 竞争只有一个成功；Validator 另行拒绝缺少 Started、身份不一致、缺口、重复、乱序和终态后消息。

### Task C3：取消原因和取消感知 Chat 边界

状态：已完成。

修改：

- `agent-application/src/main/java/io/namei/agent/application/TurnCancellation.java`
- `agent-application/src/main/java/io/namei/agent/application/TurnCancellationSource.java`
- `agent-application/src/main/java/io/namei/agent/application/ChatUseCase.java`
- `agent-application/src/main/java/io/namei/agent/application/ChatService.java`
- `agent-bootstrap/src/main/java/io/namei/agent/bootstrap/observability/SafeChatUseCase.java`

新增/修改测试：

- `agent-application/src/test/java/io/namei/agent/application/TurnCancellationSourceTest.java`
- `agent-bootstrap/src/test/java/io/namei/agent/bootstrap/observability/SafeChatUseCaseTest.java`

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am -Dtest=TurnCancellationSourceTest,SafeChatUseCaseTest -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖 First Writer Wins、回调至多一次和观察包装层原样透传 Token。

验证证据（2026-07-15）：聚焦 Reactor 命令先因带原因 `cancel(code)`、`TurnCancellation.reason()` 和取消感知 `ChatUseCase` 缺失而在 Application 测试编译失败；实现后同一命令在 Application 执行 3 个测试、Bootstrap 执行 3 个测试，全部通过。已有无参 `cancel()` 和单参数 `chat(command)` 保持兼容；生产 `ChatService` 与 `SafeChatUseCase` 透传同一个 Token。

### Task C4：有界出站缓冲

状态：待开始。

新增：

- `agent-application/src/main/java/io/namei/agent/application/OutboundMessageSink.java`
- `agent-application/src/main/java/io/namei/agent/application/BoundedOutboundBuffer.java`
- `agent-application/src/main/java/io/namei/agent/application/OutboundDeliveryException.java`
- `agent-application/src/test/java/io/namei/agent/application/BoundedOutboundBufferTest.java`

RED/GREEN：

```bash
./mvnw -pl agent-application -am -Dtest=BoundedOutboundBufferTest -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖顺序消费、容量满、Deadline、中断、断开、错误 Turn 和终态后发布；不得使用 `sleep` 制造竞态。

### Task C5：Chat 到 Channel 的安全投影

状态：待开始。

新增：

- `agent-kernel/src/main/java/io/namei/agent/kernel/error/SessionPersistenceException.java`
- `agent-application/src/main/java/io/namei/agent/application/TurnFailureClassifier.java`
- `agent-application/src/main/java/io/namei/agent/application/MessageTurnService.java`
- `agent-application/src/test/java/io/namei/agent/application/MessageTurnServiceTest.java`

修改：

- `adapter-sqlite/src/main/java/io/namei/agent/adapter/sqlite/SqliteRepositoryException.java`

RED/GREEN：

```bash
./mvnw -pl agent-application -am -Dtest=MessageTurnServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖成功、显式取消、断开/背压原因、全部稳定错误映射、未知异常脱敏、Sink 失败不二次写入和每条路径唯一终态。

### Task C6：阶段门禁与文档收口

状态：待开始。

修改：

- 本计划、`docs/README.md`
- `docs/architecture/java-rewrite-guide.md`
- `docs/architecture/python-java-capability-matrix.md`
- `docs/roadmap/java-rewrite-roadmap.md`
- `docs/runbooks/local-development.md`

验证：

```bash
./mvnw spotless:check
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
./mvnw -pl agent-kernel dependency:tree
```

另执行 Secret、敏感文件、Kernel 禁止依赖、Python Runtime 和工作树检查。记录实际测试数和退出码，更新状态为“已实现并验证”。

## 3. 提交策略

- C0：文档与决策。
- C1：版本化 Fixture 与消息值对象。
- C2：序号状态机。
- C3：取消原因与透传。
- C4：有界背压。
- C5：安全 Turn 投影。
- C6：验收文档。

每个 Task 在聚焦 GREEN、`git diff --check` 和自审后独立提交。阶段门禁前不例行运行完整 Reactor。

## 4. 暂停条件

出现以下情况必须暂停并重新批准，而不是扩张本计划：

- 需要新增 Maven 模块、数据库表、消息中间件或外部服务。
- 需要改变现有 Conversation 原子提交或 Side Effect Ledger 语义。
- 需要开放真实网络、真实渠道、真实 Workspace、真实 MCP Server 或 Secret。
- 需要把 Thinking、Tool Arguments/Result、Memory 正文或异常正文暴露给 Channel。
- 需要自动重放、持久队列、Exactly Once 或跨进程恢复。
