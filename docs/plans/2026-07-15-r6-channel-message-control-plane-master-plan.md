# R6 渠道、消息总线与控制面总体工作计划

- 状态：已批准；R6.1–R6.4 已合入 `main`，R6.5 G0–G4 已完成并进入 G5
- 日期：2026-07-15
- 阶段：R6
- 批准记录：用户要求完整实现本计划；各子阶段仍须先冻结对应 Contract、Spec、ADR 和实施计划，真实网络、Secret 与付费 Smoke 保留独立授权门禁
- 功能基线：R6.1–R6.4 分别通过 PR #4–#7 合入；R6.4 合并后聚焦 CI 稳定性修复又通过 PR #8，以 `67f16a5` 作为 R6.5 基线
- Python 参考：`bus/`、`bootstrap/channel_host.py`、`bootstrap/channels.py`、`agent/provider.py` 和 Dashboard API
- 关联 Roadmap：[Java 重写 Roadmap](../roadmap/java-rewrite-roadmap.md)
- R6.1 Contract：[版本化渠道消息与流式运行时契约](../contracts/versioned-channel-message-runtime.md)
- R6.1 ADR：[ADR-0007：采用项目自有的有界渠道消息协议](../adr/0007-use-project-owned-bounded-channel-message-protocol.md)
- R6.1 计划：[版本化渠道消息 Contract Runtime 工作计划](2026-07-15-versioned-channel-message-runtime-implementation.md)

## 1. 目标

R6 把当前只能通过同步 HTTP 请求使用的 Java Agent，推进为支持本地 CLI、真实 Provider Streaming、至少一个真实渠道和最小安全控制面的多入口运行时。所有入口复用同一套 Chat、Tool、Memory、MCP、取消和会话提交语义，Channel Adapter 只转换输入输出，不取得业务编排权。

目标拓扑：

```text
CLI / Real Channel / Dashboard
             |
             v
     InboundMessage V1
             |
             v
        Channel Host
             |
             v
     MessageTurnService
             |
             v
 Chat / Tool / Memory / MCP
             |
             v
     OutboundMessage V1
             |
             v
  Bounded Outbound Buffer
             |
             v
CLI / Real Channel / Dashboard
```

R6 完成不等于 Python 全部渠道、插件或主动能力已经迁移。Scheduler、Proactive、Drift、Subagent 和 Peer Agent 属于 R8。

## 2. 全局不变量

以下约束贯穿所有 R6 子阶段：

1. 一个入站 Turn 最多执行一次业务闭环，且最多产生一个终态。
2. 出站序号从 `0` 开始严格连续；第一条必须是 `TURN_STARTED`。
3. `TURN_COMPLETED` 携带权威完整文本；Delta 只是有序预览，消费者无需依赖自行拼接才能获得正确结果。
4. 取消原因遵循 First Writer Wins，并从 Channel 传播到 Provider、Tool/MCP 和会话提交边界。
5. 取消、失败或断开不得把半截 Assistant 回答写入会话历史。
6. 队列、事件数、正文、并发、等待和总执行时间全部有显式上限。
7. Channel Adapter 不读取模型自由文本中的 Session/Route Override，也不编排 Chat、Tool、Memory、MCP 或审批。
8. Thinking、Memory 正文、Tool Arguments/Result、原始异常和 Provider 错误载荷不得进入渠道事件或日志。
9. 默认配置不连接真实渠道、不读取真实渠道 Secret、不启动后台连接。
10. 未冻结幂等与恢复 Contract 前，不自动重放可能调用模型、工具或副作用的整个 Turn。

## 3. 阶段总览

| 子阶段 | 名称 | 状态 | 主要结果 |
| --- | --- | --- | --- |
| R6.1 | 版本化 Message Contract Runtime | 已完成并合入 `main` | Java-owned Fixture、消息值、唯一终态、取消原因、有界背压、安全终态投影 |
| R6.2 | 本地 CLI 与 Provider Streaming | 已完成并通过 PR #5 合入 `main` | 供应商无关流式 Port、Spring AI Adapter、本地 CLI、取消与提交隔离 |
| R6.3 | Channel Host 与首个真实渠道 | 已通过 PR #6 合入 `main`；真实 Smoke 待授权 | 统一宿主、身份路由、Telegram 私聊文本、网络生命周期 |
| R6.4 | 渠道幂等、可靠投递与恢复 | 已通过 PR #7 合入 `main`；PR #8 修复合并后 CI 观察竞态 | 入站去重、投递状态、崩溃恢复和有界重试 |
| R6.5 | Dashboard 与最小控制面 | G0–G4 已完成，进入 G5 默认关闭装配 | 默认关闭的 Loopback 安全状态、SSE 和活动 Telegram Turn 取消 |
| R6.6 | 阶段总验收与灰度 | 待前序完成 | Golden、故障/压力、安全审计、Runbook 和回退 |

## 4. R6.1：版本化 Message Contract Runtime

### 4.1 已完成范围

- 40 Case 的 Java-owned `message-bus/versioned-channel-message.json`。
- 纯 JDK `InboundMessage`、`OutboundMessage`、`MessageRoute` 和稳定码。
- 严格序号、唯一终态、终态并发竞争和独立 Validator。
- `REQUESTED`、`CHANNEL_DISCONNECTED`、`BACKPRESSURE_EXCEEDED`、`SHUTDOWN` 取消原因。
- 项目自持有界缓冲、发布 Deadline、断开唤醒和背压取消。
- 现有非流式 Chat 到 Started/Completed/Cancelled/Failed 的安全投影。
- 默认 321、`failure` 63、`compat` 360 个测试及格式、依赖、Secret、敏感文件和孤儿进程审计。

### 4.2 发布证据

R6.1 已通过 PR #4 的默认、`failure`、`compat` 远程 CI 和 Review，并以 `a77b088` 合入 `main`。R6.2 从该基线创建独立分支，没有依赖未合并的 R6.1 工作树。

## 5. R6.2：本地 CLI 与 Provider Streaming

R6.2 已完成。它复用 R6.1 协议，没有新增 CLI 私有消息格式，也没有同时接入真实外部渠道。

### Task D0：冻结 R6.2 Contract、Spec、ADR 和实施计划

必须先决定：

- Provider Delta、完整完成结果、失败和取消的项目协议。
- Delta 与 Tool Call 的关系；Tool Arguments/Result 不作为渠道 Delta 暴露。
- 多次模型调用的 Tool Loop 中，哪些文本可以对外预览。
- EOF、`Ctrl+C`、stdout 故障和 Channel Disconnect 的稳定取消原因。
- 最大 Delta 数、累计字符、空闲超时、总 Deadline 和缓冲容量。
- 流式成功、取消和失败时的 SQLite 原子提交边界。
- Spring AI Streaming 只位于 Adapter；Kernel/Application 不依赖 Reactor。

纯文档任务不制造 RED。未批准这些文件前不得编写生产流式代码。

### Task D1：供应商无关的流式模型 Port

用聚焦 TDD 固定：

- `0..N` 个文本 Delta 后产生一个完整模型结果。
- 无 Delta 直接完成合法。
- 完成、失败、取消只能竞争一个模型终态。
- 终态后事件、超限事件和身份错配被拒绝。
- 取消订阅必须有界完成，不能泄漏 Provider 任务。

Kernel 只保存纯 Java 值和协议；任何 `Flux`、Spring AI 或 SDK 类型只能出现在 Adapter。

### Task D2：确定性 Fake Streaming Adapter

先实现不访问网络的 Fake，覆盖：

- 单段、多段、空白 Delta 和无 Delta 完成。
- 中途模型失败、超时和调用方取消。
- 取消/完成竞争及终态后迟到事件。
- Delta 拼接与最终完整文本不同；完成快照仍为权威结果。
- 使用闩锁、Barrier 或可控 Publisher，不使用 `sleep` 制造竞态。

### Task D3：Application 流式 Turn 编排

把安全投影扩展为：

```text
TURN_STARTED -> CONTENT_DELTA* -> TURN_COMPLETED
                                  TURN_CANCELLED
                                  TURN_FAILED
```

要求：

- Sink 成功接收事件后才推进发布状态。
- Sink 断开或背压会取消同一个 Turn Token。
- 取消/失败不提交半截回答；完整成功只提交一次。
- Tool/MCP 中间结构不写入渠道，也不改变最终 SQLite 的 `user/assistant` 轮次。
- Sink 终态写入失败不得递归尝试第二种终态。

### Task D4：Spring AI Streaming Adapter

使用受控 HTTP Stub 先 RED/GREEN，再考虑真实 Provider Smoke：

- 保留实际 Provider Options 运行时类型、模型配置和 Tool Callback。
- 正确处理正常流、空流、损坏事件、Provider 错误、超时和中途断开。
- 下游取消时终止 Provider Subscription。
- 对单事件、累计正文、事件数量和总时间执行项目上限。
- Adapter 聚合 Tool Call Fragment，只有完整 Tool Call 才进入 Application Tool Loop。
- Provider 原始正文和错误载荷不得进入稳定异常或日志。

真实网络和费用 Smoke 必须单独获得 Provider、模型、Key 和费用批准；默认测试保持离线。

### Task D5：本地 CLI Adapter

CLI 只负责：

- 从 stdin 读取有界 UTF-8 文本。
- 生成或恢复本地会话标识，并构造可信 `InboundMessage`。
- 从有界 Buffer `poll()`，按序渲染 Started、Delta 和终态。
- 把 EOF、`Ctrl+C`、stdout 关闭和宿主停机传播为取消。
- 安全显示稳定错误码，不输出原始异常、Prompt、Memory 或 Tool 数据。

首次切片保持单进程、单本地用户和明确会话选择；不引入终端 UI 框架、Shell 执行或动态命令插件。

### Task D6：Tool/Memory/MCP 流式闭环

验证现有能力被复用而不是重写：

- 普通文本回答可流式展示并提交。
- Tool/MCP 调用期间不泄漏结构化参数和结果。
- Memory Context 仍是临时 Frame，不出现在渠道正文或会话提交中。
- Tool/MCP 取消、超时和循环上限投影为唯一安全终态。
- 全局 `DISABLED` 模式仍不发布 Tool Definition 或连接 MCP。

### Task D7：R6.2 故障与并发验收

覆盖：

- 慢消费者、容量耗尽、发布超时和断开唤醒。
- Provider 正在发送 Delta 时的取消、完成和错误竞争。
- CLI 退出与会话提交竞争。
- 同 Session 串行、不同 Session 并行保持现有语义。
- 线程中断标记恢复、任务和 Subscription 零泄漏。
- 长回答和大量细碎 Delta 的字符/事件预算。

### Task D8：R6.2 阶段门禁

执行：

```bash
./mvnw spotless:check
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
./mvnw -pl agent-kernel dependency:tree
```

同时审计 Secret、敏感文件、Kernel 禁止依赖、无界队列、后台线程、Provider 任务和工作树。更新 Contract、Spec、Plan、Roadmap、能力矩阵和 Runbook 后，才能声明 R6.2 完成。

R6.2 退出门禁：CLI 完成普通回答、Tool/MCP 回答和多 Delta 回答；取消、断开、背压和失败都只有一个终态；SQLite 只提交完整成功轮次。

完成证据（2026-07-15）：Java-owned Streaming/CLI Fixture 的 Kernel/Application/CLI 6/9/6 共 21 个 Case 由生产实现消费；本地 OpenAI-compatible SSE Stub 验证文本 Chunk、Tool Schema/Call/Result、Options 保留和目标连接取消；CLI、Tool/MCP、输出故障、背压、断开、关闭及临时真实 SQLite 提交隔离均通过。最终默认门禁 363 个测试（345 单元、18 集成）、`failure` 99 个（96 单元、3 集成）、`compat` 402 个（383 单元、19 集成）全部通过，Kernel 生产依赖与安全/资源审计通过。

R6.2 没有访问真实外部 Provider/渠道、Secret、付费服务或用户工作区。其完成状态不表示渠道可部署，也不扩大 R6.3 的网络、身份或数据授权。

## 6. R6.3：Channel Host 与首个真实渠道

用户已选择 Python 已有且代表性明确的 Telegram，并批准 [Telegram Channel Host 契约](../contracts/telegram-channel-host.md)、[设计](../specs/2026-07-16-telegram-channel-host-design.md)、[ADR-0009](../adr/0009-use-jdk-httpclient-for-telegram-long-polling.md)和[实施计划](2026-07-16-telegram-channel-host-implementation.md)中的离线范围。JDK HTTP 长轮询、数值身份和默认关闭实现已使用 Fake Server 与固定脱敏样本完成本地验收；真实 Token、网络、用户数据和 Smoke 继续后置。

### Task E0：冻结 Channel Adapter/Host Contract

固定：

- Adapter 启停、健康、接收、发送、取消和关闭协议。
- 外部 Channel/User/Conversation 到内部 Route/Session 的可信映射。
- 渠道事件 ID、重复投递、乱序、编辑和删除的处理边界。
- 文本转义、平台长度分片、格式降级和速率限制。
- 单渠道故障隔离及应用停机顺序。

### Task E1：Channel Host 生命周期

- 装配零个或多个显式启用的 Adapter。
- 默认禁用时零网络、零 Secret 读取、零后台线程。
- 单个 Adapter 启动失败不泄漏资源；是否阻断整个应用由 Contract 固定。
- 关闭时先停止入站，再取消活动 Turn，最后有界排空或丢弃预览队列。

### Task E2：身份、Route 和 Session 映射

- 外部标识只由可信 Adapter 读取，模型正文不能覆盖。
- 使用稳定、不可碰撞且日志可脱敏的内部标识。
- 多用户、多会话和群组/私聊边界有 Golden。
- 未认证或不在 Allowlist 的输入在创建 Turn 前拒绝。

### Task E3：入站与出站 Adapter

- 入站消息通过 R6.1 构造器验证并送入同一 Application 入口。
- 出站按严格序号消费；平台分片不改变内部 Message Contract。
- Delta 可按渠道能力合并或节流，终态必须包含完整权威结果。
- 平台发送失败转换为稳定 Delivery 状态，不把原始响应泄漏给 Core。

### Task E4：网络生命周期与限流

- 连接、请求、发送和关闭全部有 Deadline。
- 重连有次数、退避和总时间上限，不无限后台重试。
- 平台限流只重试可安全重试的投递，不重放整个 Agent Turn。
- 单渠道积压不能耗尽全局线程、内存或模型并发。

### Task E5：真实渠道验收

- 默认 CI 使用 Fake/Stub，验证协议、身份、分片、限流、断开和重连。
- 获得真实 Token、网络和数据范围授权后运行单独 Smoke。
- Smoke 使用专用测试会话和无敏感内容，不进入默认 Profile。

R6.3 退出门禁：CLI 与一个真实渠道复用同一 Message Contract；相同 Golden Turn 得到一致终态；默认配置零网络；渠道断开不会留下活动 Turn 或任务。

本地离线完成证据（2026-07-16）：Manifest 管理的 Telegram Fixture 有 24 个 Case，聚焦生产
消费测试 25 个；真实 `JdkTelegramBotApi` + Loopback Fake Server + `MessageTurnService` 的 4 个
纵向场景覆盖权威终态、429、目标取消、另一 Chat 存活、Poll 断开和关闭。最终默认 455 个测试
（413 单元、42 集成）、`failure` 119 个（113 单元、6 集成）、`compat` 519 个（476 单元、
43 集成）全部通过，格式、依赖、Secret、无界并发、线程/Socket 和工作树审计通过。该证据只满足
R6.3 离线门禁；真实渠道 Smoke 和部署仍待独立授权。

## 7. R6.4：渠道幂等、可靠投递与恢复

R6.4 是条件阶段。只有 R6.3 证明进程重启和平台重复投递需要持久状态后才实施；它涉及新 SQLite Schema，必须先批准 Schema、迁移、备份和回退 Contract。

2026-07-16 用户已批准独立 `workspace/channels/channel-ledger.db`、渠道实例分区、Inbox/Turn Claim、事务 Outbox、Telegram Receipt、`UNKNOWN`、恢复/清理和回退的 [Contract](../contracts/channel-reliable-delivery.md)、[ADR-0010](../adr/0010-use-dedicated-sqlite-channel-ledger.md)、[设计](../specs/2026-07-16-channel-reliable-delivery-design.md)与[连续 TDD 计划](2026-07-16-channel-reliable-delivery-implementation.md)。F1–F13、Review 修复、故障矩阵、Compat 和临时数据库回退演练已完成，并通过 PR #7 合入 `main`；合并后 CI 观察竞态由不改生产语义的 PR #8 修复，主分支三套门禁全绿。真实 Telegram 仍未授权。

### Task F0：冻结幂等和恢复语义

明确区分：

```text
Turn Execution Idempotency != Outbound Delivery Retry
```

优先目标是可证明的“同一外部消息最多启动一个 Turn”和“投递可审计”；不宣称无法证明的 Exactly Once。

### Task F1：版本化 Inbox/Delivery Schema

- 使用 Java 自有增量 Schema，不修改 Python 既有核心表含义。
- 外部消息 ID、内部 Turn ID、投递目标和状态有唯一约束。
- 未知、成功、永久失败和可安全重试状态明确。
- 迁移在临时数据库完成 RED/GREEN、回滚和幂等初始化测试。

### Task F2：入站去重与并发占用

- 同一平台消息并发到达只允许一个执行者。
- 已成功 Turn 返回已有结果或稳定状态，不重新调用模型/Tool。
- 占用状态在崩溃后按明确 Lease/Recovery 规则处理。

### Task F3：出站投递状态与重试

- 重试键绑定目标、Turn、终态和内容指纹。
- 只重试渠道投递，不重新运行 Agent Turn。
- 超时后无法确认结果进入 `UNKNOWN`，不得盲目生成新幂等键。
- 重试次数、退避、生命周期和存储增长全部有上限。

### Task F4：恢复与清理

- 启动恢复不阻塞普通健康检查和未受影响渠道。
- 过期预览事件可以按 Contract 丢弃；终态投递按稳定规则恢复。
- 数据保留、归档和删除需要显式运维规则，不无限增长。

R6.4 退出门禁：重复投递、并发竞争、发送后崩溃、提交前崩溃、重启和 `UNKNOWN` 全部由故障注入测试证明；备份与回退 Runbook 可执行。

## 8. R6.5：Dashboard 与最小控制面

初始阶段保持现有 React/Vite 前端不变，先冻结 Java Control Plane API。未完成认证、TLS 和权限 Contract 前只允许 Loopback。

2026-07-17 已批准 [Loopback 控制面 Contract](../contracts/loopback-control-plane.md)、[ADR-0011](../adr/0011-use-authenticated-sse-for-loopback-control-events.md)、[设计](../specs/2026-07-17-loopback-control-plane-design.md)和[连续 TDD 计划](2026-07-17-r6-loopback-control-plane-implementation.md)。V1 明确默认 Disabled、进程内 Bearer Session、Fetch SSE、无历史重放、独立有界 Subscriber，以及只管理 Servlet 模式中当前存活的 Telegram 普通/可靠 Turn。当前已完成详细计划 G0–G4，进入 G5 默认关闭装配；CLI、同步 `/api/v1/chat`、`EXECUTION_UNKNOWN`、远程控制和 Python Dashboard 写接口仍不在 V1。

本节的 G0–G4 只表达 R6.5 阶段级能力分解，不作为当前代码任务编号。实际执行状态、RED/GREEN 命令和提交边界统一以[详细实施计划](2026-07-17-r6-loopback-control-plane-implementation.md)的 G0–G12 为准。

### Task G0：冻结 API、安全和流协议

- 在 SSE 与 WebSocket 中选择一种并记录 ADR，不同时维护两套协议。
- 固定状态查询、活动 Turn、事件订阅和取消 API。
- 明确 Loopback、Origin、CSRF、认证和授权边界。
- 默认响应只包含稳定 ID、状态、计数、时间和错误码。

### Task G1：只读状态面

- 展示 Adapter 状态、活动 Turn 数、缓冲压力和稳定健康码。
- 不展示完整 Prompt、Memory、消息正文、Tool 数据、Secret 或真实路径。
- 指标查询失败不影响 Agent 主链路。

### Task G2：事件流

- 将 R6.1 Outbound Message 投影到选定流协议。
- 新订阅者不自动重放完整敏感历史。
- 慢 Dashboard 消费者使用独立有界缓冲，不能反压真实渠道或 Agent Core。
- 断开后清理订阅和资源。

### Task G3：活动 Turn 取消

- 取消请求必须绑定可信身份和当前活动 Turn。
- 重复取消幂等，已终态返回稳定结果。
- 取消只影响目标 Turn，不允许任意修改会话、配置或 Tool 权限。
- 形成安全审计事件但不记录消息正文。

### Task G4：前端兼容

- 先用 Contract Test 固定现有前端依赖的核心字段。
- 只有 API 稳定后才修改 React/Vite；不在同一 Task 重写 UI 框架。
- 页面至少覆盖连接状态、流式回答、稳定错误和取消反馈。

R6.5 退出门禁：Loopback Dashboard 能安全观察和取消活动 Turn；断开、慢消费者和重复取消有确定语义；未经认证的远程访问不可用。

## 9. R6.6：阶段总验收、灰度与回退

### Task H0：统一 Golden

- CLI 与真实渠道消费同一入站/出站 Fixture。
- 覆盖普通文本、Tool/MCP、取消、失败、断开、重复投递和最终提交。
- Java-owned Fixture 更新必须记录批准的语义变化和 Manifest Hash。

### Task H1：故障、压力和泄漏验证

- 大量细碎 Delta、慢消费者、Buffer 满和超时。
- Provider/渠道中途断开、重连、限流和损坏数据。
- 取消/完成/失败、关闭/重连和重复投递竞争。
- 长时间运行后的线程、Subscription、Socket、子进程和内存检查。

### Task H2：安全审计

- Secret、Authorization、消息正文、Memory 和工具数据泄漏扫描。
- Route/Session/User 日志脱敏和错误码稳定性。
- 默认配置零真实渠道网络、零动态下载、零 Shell。
- Kernel 继续无 Spring、Reactor、JDBC 和渠道 SDK 生产依赖。

### Task H3：部署与回退 Runbook

- 各 Adapter 默认关闭、逐项启用和健康确认步骤。
- 真实渠道 Secret 注入、轮换和撤销流程。
- SQLite 备份、Schema 校验、恢复和停机顺序。
- 灰度观察指标、停止条件和一键退回同步 HTTP/CLI 的步骤。

### Task H4：最终阶段门禁

默认、`failure`、`compat`、依赖和安全门禁全部为零失败；经授权的 Provider/真实渠道 Smoke 分开记录，不能用真实凭证作为默认 CI 前提。

R6 总退出条件：

1. CLI 与至少一个真实渠道的 Golden 会话通过。
2. Provider Streaming 的顺序、完成、取消、断线和背压语义可验证。
3. 渠道重复投递和重启恢复不会不可控地重复 Turn 或副作用。
4. Dashboard 核心路径在安全边界内兼容。
5. 默认部署仍不连接未授权渠道，回退步骤完成演练。

## 10. 提交和合并策略

- 每个子阶段使用独立短生命周期分支，不在 R6.1 分支直接累积全部 R6。
- 每个 Task 保留一组有效 RED/GREEN；文档、纯验收和构建修正遵循项目例外规则。
- 每个可观察行为独立提交，避免把 Provider、CLI、真实渠道和 Dashboard 混入同一提交。
- 子阶段完成后先跑本地阶段门禁，再推送、创建 Draft PR、跑远程 CI、Review 和合并。
- 下一子阶段从最新 `main` 创建，不依赖未合并分支。
- 从 2026-07-17 起，默认、`failure`、`compat` 与 `real-model-smoke` 使用互斥 Tag 集合；阶段需要完整离线证据时分别执行前三组，不再让 Profile 隐式重复默认回归。

## 11. 暂停与重新批准条件

遇到以下任一情况必须暂停当前 Task：

- 需要新增 Maven 模块、数据库表、消息中间件或分布式基础设施，但当前 Spec 未批准。
- 需要真实 Provider/渠道网络、Secret、付费调用或真实用户数据。
- 需要修改 Python 既有会话 Schema、真实 Workspace 或自动迁移数据。
- 需要自动重放整个 Turn、声称 Exactly Once 或改变 Side Effect Ledger 语义。
- 需要把 Thinking、Tool Arguments/Result、Memory 正文或原始异常暴露给渠道。
- 需要远程开放 Dashboard，但认证、TLS 和权限 Contract 尚未完成。
- 需要同时推进远程 MCP、副作用工具、插件、主动任务或 Subagent。

## 12. 当前立即执行顺序

1. R6.1 已完成 PR、远程三套 CI 并合入 `main`。
2. R6.2 已完成 Contract、ADR、Fixture、连续 TDD、本地/远程三套门禁，并通过 PR #5 合入 `main`。
3. R6.3 Channel Host/Telegram 离线纵向切片已完成连续 TDD，并通过 PR #6 默认、`failure`、`compat` 远程门禁合入 `main`。
4. 真实 Token、网络、费用和数据范围仍未获授权，因此真实 Telegram Smoke 必须保持禁用。
5. R6.4 可靠投递已通过 PR #7 合入 `main`；聚焦 CI 稳定性修复 PR #8 和对应主分支默认、`failure`、`compat` 门禁全部通过，仍不实现自动重放或 Exactly Once。
6. R6.5 已完成详细计划 G0–G4，当前从 G5 连续实现默认关闭装配、Loopback 安全、状态/取消 API 和认证 SSE；真实 Telegram Smoke、远程访问、CLI+Web 与前端继续等待独立授权。
