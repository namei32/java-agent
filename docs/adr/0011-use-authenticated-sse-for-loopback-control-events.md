# ADR-0011：Loopback 控制面事件采用认证 SSE

- 状态：提议
- 日期：2026-07-17
- 阶段：R6.5
- 批准记录：尚未批准；本 ADR 不能作为生产实现授权
- 关联 Contract：[Loopback 控制面契约](../contracts/loopback-control-plane.md)
- 关联设计：[Loopback 控制面设计](../specs/2026-07-17-loopback-control-plane-design.md)
- 前置决策：[ADR-0007：采用项目自有的有界渠道消息协议](0007-use-project-owned-bounded-channel-message-protocol.md)
- 前置决策：[ADR-0008：使用项目自有同步流式观察协议桥接 Provider](0008-use-project-owned-synchronous-stream-observer.md)

## 背景

R6.1 的 `OutboundMessage` 已是单向、有序、唯一终态的项目协议。R6.5 Dashboard 需要观察活动 Turn 的未来事件，但状态查询和取消仍是普通请求/响应操作。当前项目使用 Spring MVC/Servlet，没有 Reactor、WebFlux 或实时双向协议依赖。

控制面还必须满足：

- 新订阅不回放敏感历史。
- 每个消费者独立有界，慢 Dashboard 不反压 Agent 或真实渠道。
- 默认 Disabled 不创建连接或后台 Worker。
- 认证凭证不能放在 URL。
- 终态、断开、Session 到期和应用关闭能释放资源。

## 决策

1. R6.5 V1 的事件传输使用 HTTP Server-Sent Events，状态、活动 Turn 查询和取消继续使用 JSON HTTP。
2. SSE 基于现有 Spring MVC/Servlet 和 JDK 并发原语实现，不引入 WebSocket、WebFlux、Reactor 或消息 Broker。
3. SSE 请求必须携带 `Authorization: Bearer`。浏览器客户端使用 Fetch Streaming 读取 `text/event-stream`；不使用无法自定义 Authorization Header 的原生 `EventSource`。
4. 每个订阅有独立固定容量队列和最大生命期。Publisher 只做非阻塞 `offer`；满队列只断开该订阅。
5. SSE 只投影主 Outbound Sink 已接受的未来消息。连接先发送无正文 `stream.opened` 快照；V1 不保存历史，不支持 `Last-Event-ID` 重放。
6. SSE `id` 是单订阅传输序号，R6.1 `sequence` 继续是 Turn 消息序号，两者不得混用。
7. Terminal Message 结束流；心跳使用 Comment。流结束、客户端断开或观察失败不取消 Turn。

## 理由

- 数据方向本来就是 Agent 到 Dashboard；取消已有独立 HTTP API，不需要双向 Socket。
- SSE 保留 HTTP 认证、状态码、代理前安全拒绝和可读协议，且能直接表达事件名称、ID 与终态关闭。
- Spring MVC 可以让每个已认证请求在有界 Virtual Thread 中等待自己的队列，不需要新增异步框架。
- Fetch Streaming 能安全携带 Bearer Header，避免 Token 出现在 Query、浏览器历史、Referer 或访问日志。
- 明确不重放让 V1 不必持久化 Assistant 正文，也避免新订阅者取得此前敏感输出。
- 独立队列把 Dashboard 的速度和真实渠道/Agent 的背压域分开。

## 备选方案

| 方案 | 优点 | 拒绝/后置原因 |
| --- | --- | --- |
| WebSocket | 双向、低协议开销 | V1 的反向动作只有取消，HTTP 已足够；增加握手、认证、心跳、帧和关闭竞态 |
| 长轮询 | 实现直观 | 重复请求、游标和历史缓存语义更复杂；容易诱导实现事件重放 |
| 固定间隔轮询状态 | 无长连接 | 看不到 Delta，延迟和请求量更高 |
| 原生 `EventSource` + Query Token | 浏览器 API 简单 | Token 会进入 URL、历史、Referer 和访问日志，禁止 |
| Cookie + 原生 `EventSource` | 自动携带凭证 | 引入 Cookie 生命周期、SameSite 与 CSRF 边界；超出 V1 最小 Session |
| WebFlux/Reactor SSE | 背压算子丰富 | 新运行时和依赖与当前 Servlet 架构不成比例，也不能替代项目自己的有界语义 |
| 持久事件日志 + `Last-Event-ID` | 可断线续传 | 需要保存 Assistant 正文、保留/清理/加密和访问审计，风险超出 V1 |

## 后果

正面结果：

- 控制 API 仍是普通 HTTP，事件流只有一套协议。
- 不新增框架依赖、数据库或跨进程组件。
- 认证 Token 不进入 URL，慢消费者故障域明确。
- Message Contract 顺序与 SSE 传输顺序可独立测试。

代价：

- 浏览器必须实现 Fetch Stream Parser，不能直接调用原生 `EventSource`。
- 连接断开后无法恢复遗漏 Delta；客户端只能重新查询状态并订阅仍活动的 Turn。
- 每个 SSE 占用一个受上限保护的 Servlet 请求/Virtual Thread。
- 没有历史事件意味着终态后新订阅只能得到 `ALREADY_TERMINAL`，不能读取此前回答。

## 重新评估条件

出现以下任一需求时新增 ADR：

- 需要远程、多用户或跨代理 Dashboard。
- 需要断线续传、持久历史或跨进程 Event Bus。
- 控制动作扩展为高频双向交互。
- 实测 Subscriber 数量或连接寿命超过 Servlet/Virtual Thread 的批准上限。
- 必须支持原生 `EventSource`、Cookie 或浏览器跨 Origin。

## 实施验证要求

实现必须使用可控 Writer、Clock 和队列做确定性测试，覆盖未来事件、无重放、终态、慢消费者、断开、Session 到期和 Shutdown；必须证明 Observer 失败不影响主 Sink，并通过默认、`failure`、`compat` 及线程/订阅泄漏门禁。
