# R6.5 Loopback 控制面、安全状态、SSE 与活动 Turn 取消工作计划

- 状态：G0 已批准；从 G1 开始连续 TDD
- 日期：2026-07-17
- 分支：`agent/r6-control-plane-contract`
- Worktree：`/Users/namei/idea/agent/java-agent-r6-control-plane`
- 基线：`origin/main@67f16a589061975adc4870a580b1a2f70df57973`
- Contract：[Loopback 控制面契约](../contracts/loopback-control-plane.md)
- Spec：[Loopback 控制面设计](../specs/2026-07-17-loopback-control-plane-design.md)
- ADR：[ADR-0011：Loopback 控制面事件采用认证 SSE](../adr/0011-use-authenticated-sse-for-loopback-control-events.md)
- 总体计划：[R6 渠道、消息总线与控制面总体工作计划](2026-07-15-r6-channel-message-control-plane-master-plan.md)

> 用户已于 2026-07-17 明确批准关联 Contract、Spec、ADR 和本计划，并授权从 G1 开始连续 TDD。远程访问、真实 Telegram、CLI+Web、前端和 Ledger Reconcile 继续冻结。

## 1. 目标

按连续 TDD 建立默认关闭、仅 Loopback、单 JVM 的后端控制面：

```text
authenticated local Operator Session
  -> safe Channel/Control status
  -> active Telegram Turn list
  -> future-only bounded SSE
  -> target REQUESTED cancellation
```

后端完成只声明“认证 Loopback Control API 已离线验证”。现有 React/Vite 如何归属和同源托管需要单独前端 Contract；远程访问、真实 Telegram、用户数据和部署不因本计划获得授权。

## 2. 实施原则

1. G0 获批后从 G1 开始；行为 Task 使用一次聚焦 RED、最小 GREEN、Refactor、自审和独立提交。
2. Fixture-only、文档和最终验收不人为破坏已有生产实现制造 RED。
3. 随机数、Clock、Writer、Thread、Barrier、Latch 和故障点可注入；不使用墙钟 Sleep 或全局 Thread 枚举。
4. 所有 HTTP 集成只使用 `127.0.0.1` Ephemeral Port；所有 SQLite 使用 `@TempDir`。
5. Observer、Session、订阅和审计必须有硬上限；控制面失败不进入 Agent 主失败路径。
6. R6.4 Ledger 是只读状态来源；测试逐表证明取消不修改 Claim/Outbox/Delivery。
7. G4、G8、G10 执行集中阶段门禁；其他 Task 不重复跑完整 Reactor。
8. 原始脏工作树 `/Users/namei/idea/agent/java-agent`、Python 仓库和真实 Workspace 不修改。
9. 任一行为需要 Cookie/CORS、远程访问、历史存储、新依赖/模块、CLI+Web 或 Python Dashboard 写接口时立即暂停。
10. 从 2026-07-17 起，默认、`failure`、`compat` 和 `real-model-smoke` 按 Tag 互斥选择；G10 分别执行前三组获得完整离线证据，不重复执行同一用例。
11. 聚焦命令一次选择当前 Task 的全部目标类；若选择器同时包含常规和 `failure` 方法，显式覆盖为 `-Dexcluded.test.groups=compat,real-model`，不拆成多次 Maven 启动。

测试策略优化证据（2026-07-17）：默认、`failure`、`compat` 分别执行 461、138、106 项并全部通过，
本地用时分别为 31.438、37.283、12.011 秒；三组共 705 项且互不重复。旧规则在相同代码状态下
会累计选择 1,442 项，互斥选择减少 737 次重复执行，约 51.1%。G4 精确选择器仍在一次 Maven
调用中执行 26 项并全部通过，用时 4.181 秒。真实模型 Profile 只检查 Effective POM，未实际执行。

## 3. Task G0：冻结 Contract、Spec、ADR 与计划

状态：已完成并批准。

交付：

- `docs/contracts/loopback-control-plane.md`
- `docs/specs/2026-07-17-loopback-control-plane-design.md`
- `docs/adr/0011-use-authenticated-sse-for-loopback-control-events.md`
- 本实施计划
- 文档导航、R6 总计划、Roadmap、重写指南和能力矩阵状态同步

必须明确批准：

- `DISABLED|LOOPBACK`、严格 Loopback/Host/Origin 和默认零控制面。
- 进程内 256-bit Bearer Session、无 Cookie、无 CORS、无 URL Token。
- Fetch Streaming + SSE、无历史/无 `Last-Event-ID` 重放、独立有界队列。
- 只管理 Servlet 模式 Telegram Volatile/Reliable 活动 Turn；CLI、同步 HTTP 和 `EXECUTION_UNKNOWN` 排除。
- 复用已有取消源、First Writer Wins、Tombstone、资源上限和安全审计。
- 后端完成后先暂停并批准独立前端 Contract。

验证：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
git diff --check
```

预计提交：`docs: 提议 R6.5 Loopback 控制面契约`

文档验证证据（2026-07-17）：`spotless:check` 对八模块 Reactor 全部通过，`git diff --check`
和本地 Markdown 相对链接检查通过。当前变更只包含 Contract、Spec、ADR、Plan 与阶段状态文档；
没有创建 48 Case Fixture、Token、Registry、HTTP Mapping、生产代码、线程、数据库或网络访问。

批准证据（2026-07-17）：用户明确回复“批准 R6.5 G0，按计划从 G1 开始连续 TDD”，并再次要求
继续冻结远程访问、真实 Telegram、CLI+Web 和前端变更。

## 4. Task G1：Java-owned Fixture 与 Kernel Contract

状态：已完成。

先创建：

- `testdata/golden/control-plane/loopback-control-plane-v1.json`
- Manifest Hash/Case Count 更新
- `ControlPlaneContractTest`
- `ControlEventProjectionTest`

Fixture 固定 48 Case 的 Mode/Security、Session、Snapshot、Cancellation、SSE 和 Shutdown 语义。随后最小实现纯 Java：

- `ControlPlaneContract`
- `ControlTurnState`、`ControlCancelResult`、`ControlTerminalKind`
- `ControlStableCode`
- `ControlTurnRef`
- `ControlEventProjection`

RED/GREEN：

```bash
./mvnw -pl agent-kernel -am \
  -Dtest=ControlPlaneContractTest,ControlEventProjectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审：Kernel 零 Spring/Servlet/Jackson 生产依赖；`retryable` 只由稳定码派生；`turnRef` 格式和 `toString()` 脱敏；Fixture Actual 由生产 Factory 生成。

预计提交：`feat: 冻结 Loopback 控制面 Kernel Contract`

RED/GREEN 证据（2026-07-17）：聚焦命令首次在 Kernel 测试编译阶段因
`ControlPlaneContract`、`ControlTurnRef`、状态/结果/稳定码和 `ControlEventProjection` 缺失而
失败。最小实现后，同一命令执行 5 个测试全部通过：Fixture 精确固定 8/8/8/10/10/4 共 48 Case，
128-bit Turn Ref 使用无 Padding Base64URL 且安全字符串脱敏，控制错误 `retryable` 只由枚举派生，
5 个 Kernel-owned SSE Case 由生产投影消费并删除原始 Turn/Session/Route。首次 GREEN 编译暴露
既有 Message Code Parser 的包可见性，投影改为本包严格枚举解析；随后 Fixture 测试发现
`MODEL_TIMEOUT` 是 R6.1 Message Code 而非 Control Code，测试只将 `CONTROL_*` 交给控制码 Parser。
最终聚焦命令全绿，目标 Spotless 与 `git diff --check` 通过。

Fixture SHA-256 为
`34ac5617ceecef59e6458eaea4fb48ad95a800c0af861a6baf3672981c9ce3f6`，已写入 Manifest；未使用
Python、真实 Token、网络、线程、数据库或用户数据。

## 5. Task G2：Active Turn Registry、取消与 Tombstone

状态：已完成。

先写：

- `ActiveTurnRegistryTest`
- `ActiveTurnCancellationTest`
- `ActiveTurnRegistryConcurrencyTest`（`@Tag("failure")`）

RED 覆盖：

- 随机 `turnRef`、安全 Snapshot、顺序、Terminal 移除。
- Registry 容量饱和返回 No-op 且主 Turn 继续。
- 首次/重复/其他原因先赢/Terminal/过期取消。
- 两次取消、取消与 Terminal、Close 的确定性竞态。
- Tombstone TTL/容量/惰性清理和进程内语义。
- Registry 不保存原始 ID、正文或 Thread。

最小实现：

- `ActiveTurnRegistry`
- `ActiveTurnRegistration`
- `ControlCancellationHandle`
- `ActiveTurnSnapshot`
- No-op Observer/Registration

RED/GREEN：

```bash
./mvnw -pl agent-application -am \
  -Dtest=ActiveTurnRegistryTest,ActiveTurnCancellationTest,ActiveTurnRegistryConcurrencyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审：不在锁内调用可重入取消 Callback；First Writer 只由已有取消源决定；一次关闭、Tombstone 和许可释放无泄漏。

预计提交：`feat: 建立活动 Turn 控制注册表`

RED/GREEN 证据（2026-07-17）：先加入 `ActiveTurnRegistryTest`、
`ActiveTurnCancellationTest` 和带 `failure` 标签的 `ActiveTurnRegistryConcurrencyTest`，聚焦命令
在 Application 测试编译阶段因 Registry、Registration、Cancellation Handle、Snapshot 和 Outcome
类型缺失而失败。最小实现后同一命令执行 11 个测试全部通过，覆盖安全 Snapshot、严格 Message
顺序、Terminal/异常 Close、首次与重复取消、其他取消原因先赢、目标隔离、Tombstone TTL/容量、
Registry 饱和 No-op，以及双取消和取消/Terminal 的确定性竞态；测试执行时间约 0.11 秒，完整
Reactor 聚焦命令约 2.2 秒。

取消 Handle 复用现有 `TurnCancellationSource`，调用取消回调前释放 Registry 锁；Terminal 在回调期间
胜出时只返回 `ALREADY_TERMINAL`，不会复活条目。活动项与 Tombstone 不保存原始 Turn/Session、
Route、正文或 Thread，所有控制对象的字符串投影均隐藏 `turnRef`。目标 Spotless 与
`git diff --check` 通过，未使用网络、真实 Telegram、Token、数据库或用户数据。

## 6. Task G3：非阻塞 Observer Tap 与有界 Event Hub

状态：已完成。

先写：

- `ObservedOutboundMessageSinkTest`
- `ControlEventHubTest`
- `ControlEventHubConcurrencyTest`（`@Tag("failure")`）

RED 覆盖：

- Primary 接受后才观察；Primary 拒绝时零观察。
- Observer Runtime Failure 不改变 Primary 结果。
- Snapshot + Subscribe 竞态不双漏。
- 每订阅独立顺序、终态关闭和 Subscriber 计数。
- 队列满只移除慢消费者，其他订阅和 Primary 继续。
- Client Disconnect、Session Revoke、生命期和 Shutdown 幂等释放。

最小实现：

- `ObservedOutboundMessageSink`
- `ControlEventHub`
- `ControlSubscription`
- 可控 `ControlEventWriter` 边界

RED/GREEN：

```bash
./mvnw -pl agent-application -am \
  -Dtest=ObservedOutboundMessageSinkTest,ControlEventHubTest,ControlEventHubConcurrencyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审：Publisher 只做 `offer`；无每事件 Task、无 Executor、无历史缓存；慢消费者不调用 Turn Cancellation。

预计提交：`feat: 隔离控制面事件订阅背压`

RED/GREEN 证据（2026-07-17）：先加入 `ObservedOutboundMessageSinkTest`、`ControlEventHubTest` 和
带 `failure` 标签的 `ControlEventHubConcurrencyTest`，聚焦命令在测试编译阶段因 Observer Sink、
Event Hub、Subscription、Opening、Sequenced Event、Close Reason 和安全异常类型缺失而失败。最小
实现后同一 G3 命令执行 11 个测试全部通过；随后把 G2/G3 六个测试类合并回归，共 22 个测试全部
通过，Reactor 用时约 2.4 秒。

Primary Sink 始终先发布，Observer Runtime Failure 只关闭控制 Registration，不改变主渠道结果。
订阅建立、最后 Message Sequence 快照与发布共用 Registry 锁，竞态下事件必然出现在 opening 或
新队列之一；Hub 不保存历史。每个 Subscription 使用独立有界队列和从 1 开始的传输序号，Publisher
只执行非阻塞 `offer`；队列满只移除慢消费者，不请求 Turn 取消。Terminal 保留已入队事件后关闭，
Client Disconnect、Session Revoke、生命期和 Shutdown 均幂等唤醒并准确释放计数。实现没有
Executor、每事件 Task、无界容器、网络、Telegram Token 或用户数据；目标 Spotless 和
`git diff --check` 通过。

## 7. Task G4：Telegram Volatile/Reliable 纵向接入

状态：实施中。

先扩展：

- `TelegramChannelAdapterTest`
- `TelegramReliableChannelAdapterTest`
- `ReliableInboundCoordinatorTest`
- `ControlPlaneTelegramIntegrationTest`

RED 覆盖：

- Volatile Turn 在 Application 前注册，同一 Buffer 负责真实取消。
- Reliable Turn 只在 Claim 已是 `RUNNING` 后注册，同一 `TurnCancellationSource` 负责取消。
- 普通/可靠 Sink 都在 Primary 成功后投影 Started/Delta/Terminal。
- Durable Terminal Commit 失败时无假 Terminal，Registration `SOURCE_ENDED`，Ledger 按原语义恢复 Unknown。
- Telegram `/cancel`、Dashboard Cancel、Disconnect、Backpressure 和 Shutdown First Writer 竞争。
- Registry 饱和/Observer 失败不改变 Agent 调用次数、Channel 终态或 Ledger。
- `EXECUTION_UNKNOWN` 没有 Registry 项。

聚焦：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=TelegramChannelAdapterTest,TelegramReliableChannelAdapterTest,ReliableInboundCoordinatorTest,ControlPlaneTelegramIntegrationTest \
  -Dexcluded.test.groups=compat,real-model \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

阶段门禁：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
```

自审：Disabled 使用 No-op；CLI 构造/行为不变；R6.4 Schema、状态、重试和 SQL 无变化。

预计提交：`feat: 接入 Telegram 活动 Turn 控制观察`

## 8. Task G5：配置、模式与默认零副作用

状态：待实施。

先写：

- `ControlPlanePropertiesTest`
- `ControlPlaneConfigurationTest`
- `ControlPlaneDisabledBootstrapTest`
- `ControlPlaneLoopbackBindingTest`

RED 覆盖：

- 默认 Disabled、大小写严格、所有默认/硬边界。
- LOOPBACK + `127.0.0.1`/`::1` 允许；Wildcard、主机网卡和无法证明地址启动失败。
- `--cli + LOOPBACK` 稳定拒绝。
- Disabled 无 HTTP Mapping、SecureRandom 调用、Registry、Session、Subscriber、线程和文件。
- LOOPBACK 只装配单例 Runtime，仍不启动后台 Worker。

实现 `ControlPlaneProperties/Mode/Configuration/Runtime` 和 `application.yml` 默认项。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=ControlPlanePropertiesTest,ControlPlaneConfigurationTest,ControlPlaneDisabledBootstrapTest,ControlPlaneLoopbackBindingTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预计提交：`feat: 默认关闭 Loopback 控制面装配`

## 9. Task G6：Loopback Guard、Operator Session 与安全审计

状态：待实施。

先写：

- `LoopbackRequestGuardTest`
- `OperatorSessionStoreTest`
- `ControlPlaneSecurityFilterTest`
- `ControlPlaneAuditTest`
- `ControlPlaneSecurityFailureTest`（`@Tag("failure")`）

RED 覆盖：

- RemoteAddr、IPv4/Bracketed IPv6 Host、非法/重复 Host、Origin 和 Forwarded Header。
- 无 CORS、预检拒绝、非空/Chunked Body 拒绝、Server Request ID。
- 256-bit Token、一次返回、摘要存储、常量时间比较、硬到期、容量和注销。
- 缺失/错误/未知/过期 Token 统一 401。
- Session 到期/注销关闭其 SSE 但不取消 Turn。
- Token/摘要/Header/原始 TurnRef/正文 Marker 不进入响应、异常、`toString()` 或审计。
- Audit Sink 失败隔离。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=LoopbackRequestGuardTest,OperatorSessionStoreTest,ControlPlaneSecurityFilterTest,ControlPlaneAuditTest,ControlPlaneSecurityFailureTest \
  -Dexcluded.test.groups=compat,real-model \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预计提交：`feat: 保护本地 Operator Session`

## 10. Task G7：Status、Turns 与 Cancel HTTP API

状态：待实施。

先写：

- `ControlPlaneStatusServiceTest`
- `ControlPlaneControllerTest`
- `ControlPlaneCancellationIT`

RED 覆盖：

- Contract V1 JSON 精确字段、排序、时间、状态码和 Error Envelope。
- Channel Snapshot 异常降级，不影响其他 Channel/Agent。
- 活动项不含原始 ID/正文；Tombstone 和 Unknown 不列出。
- 首次 202、重复/其他原因/终态 200、未知 404。
- Body/Query/客户端 Reason 拒绝。
- Cancel 后最终 Message/会话提交语义仍由现有 Runtime 决定。
- Reliable Cancel 前后 Ledger 表内容、Revision 和尝试次数不变。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=ControlPlaneStatusServiceTest,ControlPlaneControllerTest \
  -Dit.test=ControlPlaneCancellationIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

预计提交：`feat: 暴露安全控制状态与目标取消`

## 11. Task G8：认证 SSE 纵向切片

状态：待实施。

先写：

- `ControlPlaneSseControllerTest`
- `ControlPlaneSseIT`
- `ControlPlaneSseFailureIT`（`@Tag("failure")`）

RED 覆盖：

- Bearer + `Accept`、opened Frame、独立 SSE ID、未来 Message Sequence。
- 新订阅无历史、`Last-Event-ID` 409、终态后 409。
- Delta/Completed 允许 Assistant 正文；所有原始 ID、Thinking/Tool/Memory/Exception Marker 缺席。
- Terminal 正常关闭、Heartbeat Comment、最大生命期。
- Writer I/O、慢队列、Session 到期/注销和 Shutdown 释放。
- 两个 Subscriber 中一个慢/断开，另一个及 Telegram Sink 完整收到终态。
- Reliable Terminal 只有 Outbox Commit 后可见，Commit 失败不可见。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=ControlPlaneSseControllerTest \
  -Dit.test=ControlPlaneSseIT,ControlPlaneSseFailureIT \
  -Dexcluded.test.groups=compat,real-model \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

阶段门禁：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
```

预计提交：`feat: 流式输出认证控制事件`

## 12. Task G9：Contract Compat、安全与并发故障矩阵

状态：待实施。

新增/扩展：

- `LoopbackControlPlaneGoldenTest`（`@Tag("compat")`）完整消费 48 Case。
- `ControlPlaneConcurrencyFailureIT`（`@Tag("failure")`）。
- `ControlPlaneSecurityIT`。
- `ControlPlaneShutdownIT`。

故障矩阵：

- Register 前/后、Started 前/后、Terminal 与 Tombstone 前/后。
- Cancel 与 Telegram Control/Disconnect/Backpressure/Shutdown/Terminal。
- Snapshot、Observer、Audit、Writer 失败。
- Session 创建/到期/注销与 Subscription 建立竞争。
- Queue 满、Subscriber 容量满、Registry/Tombstone 容量满。
- Reliable Claim Running、Terminal Commit 失败和 `EXECUTION_UNKNOWN`。
- 两种 Spring 销毁顺序和共享关闭 Deadline。

聚焦：

```bash
./mvnw -pl agent-bootstrap -am -Pfailure \
  -Dit.test=ControlPlaneConcurrencyFailureIT,ControlPlaneShutdownIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify

./mvnw -pl agent-bootstrap -am -Pcompat \
  -Dtest=LoopbackControlPlaneGoldenTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审：记录调用次数和最终取消原因；所有 Queue/Map/线程有上限；无 Sleep、外网、真实 Secret、工作区或访问日志泄漏。

预计提交：`test: 固定控制面安全与并发语义`

## 13. Task G10：后端最终门禁、Review、PR 与合并

状态：待实施。

先更新 Contract/Spec/ADR/计划状态、Manifest Hash、文档导航、R6 总计划、Roadmap、重写指南、能力矩阵和配置说明。记录实际测试数，不预填。

最终命令：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
./mvnw --batch-mode --no-transfer-progress -Pcompat verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
git diff --check
```

最终审计：

- 默认无 `/api/v1/control`、Token、Registry、Subscriber、Worker 和新文件。
- LOOPBACK 不能被非 Loopback、恶意 Host/Origin 或无 Token 请求使用。
- Git/日志/JSON/SSE 无真实 Secret、Token 形态、用户正文夹具、原始 ID、DB/WAL/SHM/Backup。
- 控制面无未关闭 Subscription/请求 Thread、无界 Queue/Map/Executor。
- Kernel/Application 生产依赖方向正确；无 Spring/Servlet/JDBC/Telegram 反向依赖。
- R6.4 40 Case Fixture、Schema、Ledger 状态和 Retry 语义未变。
- 原始脏工作树、Python 仓库和真实 Workspace 未变化。

发布顺序：

1. 推送独立后端分支并创建 Draft PR，声明“默认关闭、仅离线 Loopback”。
2. 等待默认、`failure`、`compat` 远程 CI。
3. 审查安全 Filter、Token 生命周期、取消竞态、Observer 隔离和关闭资源。
4. Review/CI 全绿后转 Ready；经明确批准后合并。
5. 验证 `main` 三套 CI，再清理后端分支和 Worktree。

预计提交：`docs: 完成 R6.5 后端控制面验收`

## 14. Task G11：前端 Contract 与仓库归属批准点

状态：后端合入后执行文档分析，不直接实现。

只读盘点现有 React/Vite：

- 可复用的设计系统、组件、样式和构建版本。
- Python `/api/dashboard/*` 的 Session/删除/Proactive/Plugin 权限，明确不自动迁移。
- Java 同源静态托管、Vite Dev Proxy 和前端源码归属选择。
- 页面内存 Token、Fetch SSE Parser、无重放提示、状态/取消 UX 和 CSP。

随后提交独立 Frontend Contract/Spec/Plan。若需要复制或修改 Python 仓库、增加 Node 构建、静态资源 Maven 流程或新部署入口，必须先得到明确批准。

预计提交：`docs: 提议 R6.5 最小控制 Dashboard`

## 15. Task G12：最小 Dashboard 与 R6.5 总验收

状态：待 G11 独立批准。

目标页面只覆盖：

- 创建/注销本地 Operator Session。
- Host/Channel/可靠性与控制面状态。
- 活动 Telegram Turn 列表。
- 未来 Assistant Delta/Terminal 流和无重放提示。
- 目标取消及稳定结果。

不覆盖历史消息、删除、Proactive、Plugin、Tool 权限或 Ledger Reconcile。实现位置、构建命令、浏览器 E2E、安全 Header 和发布步骤以 G11 批准文档为准。

R6.5 最终退出门禁：同源 Loopback Dashboard 能安全观察和取消当前 Telegram Turn；慢消费者、断开、重复取消、Session 到期和 Shutdown 有确定语义；未经认证的远程访问不可用。

## 16. 暂停条件

- G0 或 G11 尚未明确批准却准备写对应生产代码。
- 需要改 R6.1 Message、R6.4 Schema/状态/Retry、会话或 Tool/Approval 语义。
- 需要 Cookie、CORS、远程代理/TLS、OAuth/OIDC、RBAC 或多用户。
- 需要历史事件、持久 Token、跨进程 Registry、Broker 或自动 Reconcile。
- 需要为 CLI 同时启动 HTTP，或把同步 `/api/v1/chat` 改成活动 Turn。
- 需要新 Maven 模块、第三方依赖、Node 构建或跨仓库写入但没有独立批准。
- 需要真实 Workspace/数据库、Telegram Token/网络、用户数据或部署。
