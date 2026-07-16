# Telegram Channel Host 工作计划

- 状态：Task E0–E9 已完成并通过本地与 PR #6 远程离线门禁；真实 Smoke 待授权
- 日期：2026-07-16
- 阶段：R6.3
- 分支：`agent/r6-telegram-channel`
- 工作树：`/Users/namei/idea/agent/java-agent-r6-telegram`
- 基线：PR #5 已以 `7e73712` 合入 `main`
- 批准记录：用户于 2026-07-16 明确批准 Contract、Spec、ADR 和本计划，并要求开始连续 TDD
- 批准范围：JDK HTTP 长轮询、数值身份、Secret 延迟读取和纯离线 Fake Server 实现
- 未授权范围：真实 Token、真实 Telegram 网络和真实用户数据
- Contract：[Telegram Channel Host 契约](../contracts/telegram-channel-host.md)
- Spec：[Telegram Channel Host 设计](../specs/2026-07-16-telegram-channel-host-design.md)
- ADR：[ADR-0009：Telegram 首渠道使用 JDK HttpClient 与 Bot API 长轮询](../adr/0009-use-jdk-httpclient-for-telegram-long-polling.md)
- 总体计划：[R6 渠道、消息总线与控制面总体工作计划](2026-07-15-r6-channel-message-control-plane-master-plan.md)

## 1. 目标

按 TDD 连续实现默认关闭、纯文本、私聊 Allowlist 的 Telegram Channel Host 纵向切片：

```text
Telegram Fake Bot API / later real Bot API
                    |
                    v
          TelegramChannelAdapter
                    |
                    v
             InboundMessage V1
                    |
                    v
           MessageTurnService
                    |
                    v
          BoundedOutboundBuffer
                    |
                    v
       Telegram authoritative terminal
```

本计划只执行离线 Fake Server。真实 Token、`api.telegram.org`、真实 Chat/User/Message、群聊、附件、Webhook、主动消息和持久 Inbox/Outbox 均为独立暂停条件。

## 2. 实施原则

1. 用户批准本文关联 Contract/Spec/ADR 后才开始生产代码。
2. 每个行为 Task 先运行一次有效 RED，再实现最小 GREEN；纯文档和 Fixture-only Task 使用项目例外规则。
3. 所有网络测试使用 Loopback `HttpServer` 和固定假 Token，不通过 DNS 访问外部服务。
4. 竞态使用 Latch、Barrier、可控 Thread Starter/Clock/Sleeper，不使用墙钟 `sleep` 证明正确性。
5. 每个 Task 完成聚焦测试、`git diff --check`、Spec/安全/并发自审后独立提交。
6. 小任务不例行执行全量门禁；E9 统一执行默认、`failure`、`compat` 和安全门禁。
7. 原始脏工作树 `/Users/namei/idea/agent/java-agent` 不修改；全部实现只在本工作树进行。

## 3. Task E0：冻结 Contract、Spec、ADR 和计划

状态：已完成并批准。

交付：

- `docs/contracts/telegram-channel-host.md`
- `docs/specs/2026-07-16-telegram-channel-host-design.md`
- `docs/adr/0009-use-jdk-httpclient-for-telegram-long-polling.md`
- 本实施计划
- 文档导航、R6 总计划、Roadmap/能力矩阵的状态同步

必须明确批准：

- JDK `HttpClient` + Bot API Long Polling，不引入第三方 Telegram SDK。
- 数值 User ID Allowlist、私聊文本和可信 Session/Route 映射。
- Telegram 端只发送权威终态，Delta 实时编辑后置。
- 默认 Disabled 路径零 Secret、零网络、零 Worker。
- R6.3 只做进程内 At-Most-One-Start，不实现持久可靠投递。

验证：纯文档任务不制造 RED；运行 `git diff --check` 与 `./mvnw --batch-mode --no-transfer-progress spotless:check`。

预计提交：`docs: 冻结 Telegram Channel Host 契约`

## 4. Task E1：通用 Channel Host 生命周期

状态：已完成。

先写 `ChannelHostTest`，固定：

- 注册顺序启动、停止入站后反向关闭。
- 单 Adapter 启动/关闭失败隔离。
- 重复启动拒绝、重复关闭幂等。
- 空 Host 不创建线程。
- 状态快照只包含稳定字段。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=ChannelHostTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最小实现：

- `ChannelAdapter`
- `ChannelState`
- `ChannelStatusSnapshot`
- `ChannelHost`

自审重点：不引用 Spring 生命周期接口；异常正文不进入快照；关闭所有 Adapter 即使前一个失败。

预计提交：`feat: 建立有界 Channel Host 生命周期`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Bootstrap 测试编译阶段因 `ChannelAdapter`、`ChannelState`、`ChannelStatusSnapshot` 和 `ChannelHost` 缺失而失败；最小实现后，同一命令执行 6 个测试并全部通过。测试证明注册顺序启动、启动失败立即清理且不阻断后续 Adapter、停止入站与反向关闭顺序、关闭故障隔离、重复启动/关闭和空 Host；失败快照只包含 `START_FAILED` 等稳定码，不包含原始异常正文。

## 5. Task E2：Telegram Fixture、配置值与可信 Mapper

状态：已完成。

先增加 Java-owned `testdata/golden/channels/telegram-channel-v1.json` 草案和 `TelegramUpdateMapperTest`，再运行有效 RED。Fixture 固定：

- 私聊 Allowlist 的 Message/Turn/Session/Route/Sender/Time 映射。
- Group、Bot、Sender/Chat 不一致、非 Allowlist、附件、空白、超限和编辑拒绝。
- `/cancel`、`/stop` 控制命令。
- 未知 JSON 字段忽略但不投影 Metadata。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=TelegramUpdateMapperTest,TelegramPropertiesTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最小实现：

- `TelegramProperties` 与硬上限验证。
- 最小 `TelegramUpdate`/`TelegramMessage` 项目值。
- `TelegramInboundDecision`。
- `TelegramUpdateMapper`。
- `TelegramIdGenerator` 与安全默认实现。

自审重点：数值 Allowlist Fail Closed；不使用 Username；外部 ID/正文不进入 `toString()`；所有 Inbound 仍经 R6.1 构造器验证。

预计提交：`feat: 固定 Telegram 私聊身份映射`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Bootstrap 测试编译阶段因 `TelegramProperties`、`TelegramUpdateMapper`、Telegram 项目值、Decision 和 ID Generator 缺失而失败；最小实现后，同一命令执行 21 个测试并全部通过。Java-owned Fixture 的 14 个场景固定了私聊 Allowlist、可信 Route/Session/Sender/Time 映射、控制命令与所有前置拒绝；配置测试固定默认关闭、数值 Allowlist 去重、全部预算硬上限和 Long Poll Deadline 关系，敏感字段渲染审计与 128-bit 安全 Turn ID 也已通过。

## 6. Task E3：JDK Bot API Client 与 Fake HTTP Server

状态：已完成。

先写 `JdkTelegramBotApiIT`，使用 Loopback `HttpServer` 覆盖：

- `getUpdates` 的 POST、UTF-8、offset、limit、正 Timeout 和 `allowed_updates=["message"]`。
- `sendMessage` 的 Chat ID、纯文本和无 `parse_mode`。
- 401/403/404、429 + `retry_after`、5xx、Timeout、中断、`ok=false`、损坏 JSON 和正文超限。
- 假 Token、URI、远端 `description` 和 Body 不进入异常。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dit.test=JdkTelegramBotApiIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

最小实现：

- `TelegramBotToken`（脱敏 `toString`）。
- `TelegramBotApi`。
- `TelegramApiException` + 稳定 Reason。
- `JdkTelegramBotApi`。
- 测试专用 `TelegramBotApiStubServer`。

自审重点：InputStream 有界读取；中断恢复；请求 Timeout 大于 Long Poll；Client 自身不重试；不输出 `HttpRequest`/URI。

预计提交：`feat: 实现有界 Telegram Bot API Client`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Bootstrap 测试编译阶段因 `TelegramBotToken`、`TelegramBotApi`、`TelegramApiException` 和 `JdkTelegramBotApi` 缺失而失败；最小实现经过 URI 首段冒号的真实 RED 修正后，Loopback Fake Server 的 20 个集成测试全部通过。测试固定 POST/UTF-8、offset/limit/timeout/allowed_updates、纯文本 `sendMessage`、未知字段白名单投影、401/403/404、429、5xx、`ok=false`、损坏/超限响应、响应头与正文阶段 Timeout、中断恢复及零隐式重试；所有异常仅保留稳定 Reason 和有界 `retry_after`，不保留 Token URI、远端 description、Body 或 Cause。

## 7. Task E4：显式请求取消的 Buffer 语义

状态：已完成。

先扩展现有 `BoundedOutboundBufferTest`：

- `requestCancellation()` 记录 `REQUESTED` 并执行回调。
- Buffer 保持可发布，Producer 能写入唯一 `TURN_CANCELLED`。
- 已有 Disconnect/Backpressure/Shutdown 原因不能被请求取消覆盖。
- 重复请求返回 false；等待 Consumer 被后续终态正常唤醒。

RED/GREEN：

```bash
./mvnw -pl agent-application -am \
  -Dtest=BoundedOutboundBufferTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

生产改动只有 `BoundedOutboundBuffer.requestCancellation()`；不改变现有状态、清空、序号或 Deadline 行为。

预计提交：`feat: 支持渠道显式取消活动 Turn`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Application 测试编译阶段因 `BoundedOutboundBuffer.requestCancellation()` 缺失而失败；加入唯一受控方法后，同一命令执行 10 个测试并全部通过。新增测试证明显式请求只以 `REQUESTED` 触发一次回调、Buffer 保持可发布并允许唯一取消终态、等待 Consumer 由后续终态正常唤醒；Disconnect、Backpressure、Shutdown 与 Requested 之间均保持 First Writer Wins，不改动队列、序号或 Deadline。

## 8. Task E5：Telegram 终态渲染、分片与限流

状态：已完成。

先写 `TelegramTextChunkerTest` 和 `TelegramTerminalRendererTest`，固定：

- Started/Delta 不发网络；Completed 使用完整终态纠正预览。
- Cancelled/Failed 只发送稳定码。
- 4,000 UTF-16 单位分片，不切断 Surrogate Pair。
- 分片严格串行。
- 只对明确 429 且等待不超预算重试一次。
- Timeout/5xx/损坏响应不重试，避免重复消息。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=TelegramTextChunkerTest,TelegramTerminalRendererTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最小实现：

- `TelegramTextChunker`
- `TelegramDeliveryPolicy`
- `ChannelSleeper`
- `TelegramTerminalRenderer`

自审重点：Renderer 用生产 Validator；不拼接 Delta 作为最终回答；失败不生成第二终态；无 Markdown/Tool/Memory 泄漏。

预计提交：`feat: 投影 Telegram 权威终态`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Bootstrap 测试编译阶段因 `TelegramTextChunker`、`TelegramDeliveryPolicy`、`ChannelSleeper` 和 `TelegramTerminalRenderer` 缺失而失败；最小实现后，同一命令执行 12 个测试并全部通过。测试证明生产 `OutboundSequenceValidator` 消费完整序列、Started/Delta 零网络、Completed 权威纠正、Cancel/Failed 稳定码投影、4,000 UTF-16 单位 Surrogate-safe 串行分片；只有明确且预算内的 429 等待后重试一次，Timeout/5xx/损坏响应、超预算 429 和第二次 429 均不重试，中断恢复且投递失败后不能伪造第二终态。

## 9. Task E6：Telegram Poll、调度、取消与进程内去重

状态：已完成。

先写 `TelegramChannelAdapterTest`，使用 Fake API、Latch 和可控 Thread Starter 覆盖：

- 单 Poll Worker、单调 offset 和同进程重复 Update 忽略。
- Worker 成功启动后才推进 offset。
- 同 Conversation 一个活动 Turn、全局 Semaphore 上限和固定 `SESSION_BUSY`。
- 合法 Update 通过 `MessageTurnService` 和 `BoundedOutboundBuffer`。
- `/cancel` 只取消目标 Chat，另一 Chat 继续完成。
- Producer/Consumer 启动失败无许可、Map 或线程泄漏。
- Unsupported/Unauthorized Update 不触达 Chat/SQLite/Tool。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=TelegramChannelAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最小实现：

- `ChannelThreadStarter`
- `TelegramChannelAdapter`
- `ActiveTelegramTurn`
- 有界 Update ID Window/offset 状态。

自审重点：没有无界 Executor/Queue；许可由外层 finally 无条件释放；模型正文不能覆盖路由；Offset 溢出 Fail Closed。

预计提交：`feat: 接入 Telegram 入站 Turn 调度`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Bootstrap 测试编译阶段因
`ChannelThreadStarter`、`TelegramChannelAdapter` 和 `ActiveTelegramTurn` 缺失而失败；
补齐测试方法的受检异常声明并加入最小实现后，同一命令执行 7 个测试并全部通过。
测试证明单 Poll Worker、单调 offset、同进程重复 Update 忽略、未授权输入业务前拒绝、
同会话与全局并发门禁、固定 Busy 回复、目标会话显式取消及无活动 Turn 回复；Outer/Producer
两级启动握手保证只有 Producer 成功启动后才推进 offset，任一级启动失败均不泄漏线程、
Conversation 占用或公平 Semaphore 许可。活动 Turn 的外层 `finally` 使用幂等清理，Worker
名称、状态和异常均不包含 Chat、Session、正文或底层异常信息。

## 10. Task E7：网络恢复、故障隔离与关闭

状态：已完成。

先写带 `@Tag("failure")` 的 `TelegramChannelFailureTest`：

- Poll Timeout/5xx/429 使用相同 offset 有界重试，成功后计数清零。
- 401/协议损坏立即 FAILED。
- 连续失败耗尽后停止入站并以 `CHANNEL_DISCONNECTED` 取消活动 Turn。
- Host Shutdown 先停止 Poll，再以 `SHUTDOWN` 取消，最后有界 Join。
- 关闭、完成、取消、失败竞争后线程/许可/活动 Map 为零。
- Poll/Turn 启动前取消与线程启动失败确定性 RED/GREEN。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am -Pfailure \
  -Dtest=TelegramChannelFailureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审重点：没有无限重连、后台 Sleep 或未 Join Worker；First Writer Wins；远端错误不泄漏。

预计提交：`feat: 完成 Telegram 网络与关闭门禁`

RED/GREEN 证据（2026-07-16）：`failure` 聚焦命令在 E6 基线上执行 11 个场景，首次有
6 个失败：Timeout/Unavailable/429 都在第一次失败后停止、401/损坏响应没有独立稳定码，
重试耗尽也没有断开活动 Turn；实现恢复状态机后，同一命令 11/11 全部通过。Poll 对
Timeout、5xx/Unavailable 和 429 在相同 offset 上最多容忍 3 次连续失败，成功即清零健康
计数；401 和协议损坏立即 Fail Closed，耗尽后以 `CHANNEL_DISCONNECTED` 取消全部活动
Buffer。关闭路径先阻止注册并中断 Poll，再以 `SHUTDOWN` 取消活动 Turn，在单一总 Deadline
的优雅/强制两阶段内 Join；门控 Worker 证明 Poll 或 Turn 任务尚未真正执行时关闭不会访问
API、调用业务或推进 offset，`REQUESTED` 与 Shutdown 竞争仍保持 First Writer Wins。所有
结束路径均验证活动 Map、线程和公平 Semaphore 许可归零/归还，底层异常正文被异步边界丢弃。

## 11. Task E8：Spring Bootstrap 与默认零网络

状态：已完成。

先写 `TelegramBootstrapTest`：

- 默认 Servlet Context 有空 Host，但无 Token 读取、API、Adapter 或 Telegram Worker。
- CLI Non-Web Context 即使环境声明 Enabled 也不装配 Telegram。
- Enabled + 空 Allowlist、缺 Token、非法 Token 或预算关系错误时启动 Fail Closed。
- Enabled + Fake Secret/API 时装配并在 Context Close 有界停止。
- 配置检查不读取 Token、不启动网络。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=TelegramBootstrapTest,CliBootstrapTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

同步：

- `ApplicationConfiguration`/独立 Telegram Configuration。
- `application.yml`。
- `.env.example`。
- 本地 Runbook 的 Disabled、Fake/测试和真实 Smoke 暂停说明。

预计提交：`feat: 装配默认关闭的 Telegram Channel`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Bootstrap 测试编译阶段因
`TelegramSecretSource` 和 `TelegramChannelConfiguration` 缺失而失败；最小条件装配、模板和
Runbook 完成后，`TelegramBootstrapTest,CliBootstrapTest` 共 13 个测试全部通过。Servlet
默认只创建并启动空 `ChannelHost`，没有 Secret Source、Token、API、Adapter、网络或 Worker；
CLI Non-Web 即使误设 Enabled 也不绑定 Telegram 配置。Enabled 路径先绑定并验证 Allowlist/
预算，再延迟读取一次 Token；缺失/非法 Token 不进入异常，注入 Fake API 后 Context Close 会
中断 Poll 并有界收敛为 `STOPPED`。额外使用真实 Spring Boot Servlet Context 执行
`PassiveChatEndpointIT`，证明 `application.yml` 的空 Allowlist Disabled 默认可绑定，既有 HTTP/
SQLite 主线正常且没有 Telegram 网络。另用“读取 Token Entry 即抛错”的受控 Environment
取得子行为 RED，再让配置检查在复制环境前剔除 `AGENT_TELEGRAM_BOT_TOKEN`；连同既有配置检查
回归共 16 个测试通过。配置检查仍在 Spring Context 前退出，YAML 不解析 Token；真实 Token、
Telegram 网络、用户数据和 Smoke 继续保持未授权。

## 12. Task E9：Golden、集成、阶段门禁与文档收口

状态：已完成。

### 12.1 Fixture-only 验收

完成 Fixture Case、Manifest Hash 和生产消费测试。Fixture-only 不人为破坏生产实现制造 RED。

```bash
./mvnw -pl agent-bootstrap -am -Pcompat \
  -Dtest=TelegramGoldenFixtureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### 12.2 纵向集成

使用真实 `JdkTelegramBotApi` + Loopback Fake Server + `MessageTurnService`：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dit.test=TelegramChannelIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

场景至少包含普通回答、多 Delta 权威纠正、目标取消、另一 Chat 存活、429、Poll 断开和关闭。

验证证据（2026-07-16）：`TelegramChannelIT` 使用生产 `JdkTelegramBotApi`、真实 Loopback
`HttpServer`、生产 `MessageTurnService` 和受控 Fake Chat 执行 4 个场景并全部通过。HTTP 请求
覆盖普通入站、多 Delta/Tool 风格预览不外发、最终权威纠正、429 明确拒绝后的单次重试、定向
`/cancel`、另一 Chat 继续完成、相同 offset 的 Poll 重试耗尽以及关闭时中断 Long Poll、取消 Turn
并 Join 全部 Worker。测试 Server 使用有界脚本队列、有界执行器并等待终止。

### 12.3 阶段门禁

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
./mvnw --batch-mode --no-transfer-progress -Pcompat verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
```

同时审计：

- Git 中无真实 Token、Chat/User/Message ID、Bot API Body 或用户正文。
- Disabled/CLI/配置检查路径无 Telegram DNS、Socket 或 Worker。
- 无界 Queue/Executor、未 Join Thread、泄漏 Semaphore、Socket 和测试 Server 为零。
- Kernel/Application 无 Telegram、HTTP Client、Jackson、Spring 或渠道 SDK 新生产依赖。
- 原始工作树和用户 Workspace 未被修改。

文档同步：Contract/Spec/ADR 状态、R6 总计划、Roadmap、能力矩阵、文档导航和 Runbook。记录实际测试数，不预填数字。

Fixture-only 证据（2026-07-16）：`telegram-channel-v1.json` 完成 24 个 Java-owned Case 并加入
Manifest，生产 Mapper、Chunker、Renderer、`BoundedOutboundBuffer` Lifecycle 和配置绑定共同
消费；聚焦命令执行 25 个测试并全部通过，Manifest Hash 校验同步通过。

阶段门禁证据（2026-07-16）：Spotless 通过；默认 `clean verify` 通过 455 个测试（413 单元、
42 集成）；`failure` 通过 119 个（113 单元、6 集成）；`compat` 通过 519 个（476 单元、43 集成）。
Kernel 依赖树只有测试依赖；Kernel/Application 生产源码无 Telegram、HTTP、Jackson、Spring 或
渠道 SDK 引用，生产源码无无界 Queue/Executor。Secret 扫描只发现环境变量名、空模板和固定假
Token；所有网络测试只访问 Loopback，关闭后的活动 Turn、Semaphore、Worker 和 Fake Server 均为
零。原始脏工作树未被本工作树修改。结论为“R6.3 离线实现已验证，真实 Smoke 待授权”。

预计提交：`test: 验收 Telegram Channel 纵向切片`

## 13. PR、远程 CI 与合并

状态：PR #6 已创建，本地与远程三套门禁已通过；真实 Smoke 待授权。

E9 全绿并自审无 Critical/Important 后：

1. 确认 `git diff --check`、工作树和提交边界。
2. 推送 `agent/r6-telegram-channel`。
3. 创建 Draft PR，说明离线范围和真实 Smoke 未授权。
4. 等待默认、`failure`、`compat` 远程 CI。
5. 修复 CI 必须先复现和定位根因；生产语义变化重新取得批准。
6. Review/CI 全绿后合入 `main`。

远程证据（2026-07-16）：PR #6 的首次 Run `29480901994` 中默认与 `failure` 通过，`compat`
暴露测试把问题与 `/cancel` 放在同一 Poll 响应造成的 Virtual Thread 调度竞态。修复只把取消与
Poll 失败放到受闩锁控制的下一次响应，确保 Turn 已进入 Chat 后再释放故障，不修改生产语义。
两个聚焦场景连续执行 5 轮全部通过，本地默认、`failure`、`compat` 再次全绿；提交 `957e824`
对应的远程 Run `29482013381` 三项均为 `SUCCESS`。

真实 Telegram Smoke 不阻塞离线代码 PR 合并，但合并后阶段状态只能写为“R6.3 离线实现已验证，真实 Smoke 待授权”，部署始终保持 `DISABLED`。Smoke 必须在单独任务中取得 Token、网络、专用测试 Chat/User、消息内容和撤销/清理授权；通过后才能声明 R6.3 真实渠道验收完成。

## 14. 暂停条件

- Contract/Spec/ADR 尚未明确批准。
- 需要真实 Token、Telegram 网络、真实 ID/正文或公网 Webhook。
- 需要第三方 Telegram SDK、新 Maven 模块、数据库 Schema 或消息中间件。
- 需要群聊、附件、主动消息、Markdown、实时编辑或跨重启可靠投递。
- 需要自动重放 Agent Turn、宣称 Exactly Once 或改变 Side Effect Ledger。
- 需要把 Thinking、Tool Arguments/Result、Memory 或原始异常发往 Telegram。
