# R13 Dashboard、控制面与渠道对齐计划

- 状态：C0 Contract/Fixture、C1 本机只读索引、C2-A 内存终态目录与 C2-B 零正文详情已完成；C3–C5 未开始
- 日期：2026-07-19
- Python 证据基线：`akashic-agent` 提交 `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Java 证据基线：`agent/r12-skill-catalog`，含 Loopback 控制面、审批 Inbox、CLI/Telegram 离线纵向切片
- 前置：[完成度审计](../architecture/2026-07-19-akashic-java-completion-audit.md)、[Tool Capability 清单](../architecture/2026-07-19-python-tool-capability-parity-inventory.md)、[Loopback 控制面契约](../contracts/loopback-control-plane.md)

## 1. 本轮结论

Python `bootstrap/dashboard_api.py` 是带 Vite 静态前端的广泛 Dashboard：它可读写 Session、Message、Memory，
可手动触发 Memory Consolidation/Optimizer，并展示 Proactive 数据；还允许 Plugin 提供 Dashboard JS/CSS。Python
渠道宿主另有 IPC、Telegram、QQ 和 Plugin Channel。它们不是一个可直接复制的“控制面”表面。

Java 已有默认关闭的 Loopback 控制面：短期 Operator Session、认证的 Status/Turn 查询、取消、单 Turn SSE，及独立的
Approval Inbox。它不提供 Dashboard 静态资源、Plugin 前端资产、Session/Message 浏览或编辑、全局 Memory 浏览、
Optimizer 控制、IPC/QQ Channel。这是正确的当前事实，而非 R13 已完成。

当前用户冻结继续生效：**不实现远程访问、真实 Telegram、CLI+Web 或前端**。C1 获得的唯一例外是已有
Loopback/Bearer 边界后的本机只读索引；本文不会以此授权网络、真实数据、渠道消息投递、持久化写入或前端文件。

## 2. 已有表面与差距

| Python 表面 | Java 已有证据 | 尚缺/语义差异 | 后续路径 |
| --- | --- | --- | --- |
| Dashboard 静态首页、Vite assets、Plugin JS/CSS panel | 无前端；Loopback 控制面只提供 API/SSE | Plugin 前端资产即不受信代码/资源加载，不能等同 Java 只读 Lifecycle Tap | C4，先有窄 API Contract，再单列前端 CSP/完整性/缓存/升级 Contract |
| Session 列表、详情、消息查询 | 状态、活动 Turn、单 Turn SSE；SQLite Session Store | Java 不提供历史浏览；Python Path 参数可携带任意 Session Key，而 Java 外部 API 目前只有安全 ID | C1/C2，用不透明 control ref 与最小投影；不在 URL、日志或响应泄漏 Channel Session ID |
| 修改/删除 Session 或 Message、批量删除 | 无 | 持久化破坏性写入；不能由 Dashboard 名义绕过 R11 审批、Capsule、Ledger、`UNKNOWN` 和恢复 | C3，在 R11-B2c 生产恢复路径完成后逐项立 Capability Contract |
| Memory 全局列表、相似查询、编辑/删除、批量删除 | 当前 Scope 的显式 HTTP 管理 API；自动 Context Retrieval | HTTP API 有意拒绝 `telegram:<chatId>`，且不提供全局/相似/编辑；Python 旧 `memory2` 已弃用 | 先完成 R12-S5 的获批只读替代；任何管理/删除仍服从 R11-B2c 和渠道身份 Contract |
| 手动 Consolidation、Optimizer 状态与触发；Proactive 数据 | R8 受限 Scheduler/Drift/NoOp Delivery | Python 触发自动记忆、外部源和可能投递；Java 目前故意不启用 | R14，不作为 Dashboard 的普通按钮迁移 |
| IPC、Telegram、QQ、Plugin Channel | CLI 和默认关闭的 Telegram 离线可靠纵向切片 | 无 IPC/QQ/插件渠道；真实 Telegram 仍冻结，Python 有媒体、实时编辑、群组和网络重试表面 | C5，每个 Channel 先独立身份、消息、恢复、关闭和真实 Smoke Contract |

## 3. 不可跨越的安全边界

1. 仅 Loopback、短期 Operator Session 和现有控制面认证可作为控制 API 起点；不因 Python Dashboard 存在而引入远程监听、Cookie、OAuth、跨域或多租户权限。
2. 不接受原始 Channel Session ID 作为 Dashboard URL、查询条件、日志字段或返回字段。`telegram:<chatId>` 已由 Java Memory HTTP API 拒绝；未来控制面需要不透明、会过期且与 Operator 绑定的引用。
3. 只读查询不获得修改、删除、优化、投递或记忆写入的权限。任何状态变更都须先完成 R11-B2c，并有独立 Capability、审批、幂等、Reservation、`UNKNOWN` 和恢复 Contract。
4. 前端和 Plugin 面板是代码/资产供应链，不是普通静态文件；不得从 Workspace、Plugin 目录或网络即席加载。
5. 各 Channel 的真实密钥、网络轮询、媒体上传、消息推送和 Provider Smoke 均保持独立操作授权，不能随控制面 API 获得。

## 4. 解冻后的连续 TDD 顺序

### C0：只读 Dashboard Message Contract Fixture（已完成，零运行时）

已冻结 20 Case 的 `control-plane/r13-read-only-control-index-v1`，详见
[C0 Contract](../contracts/r13-read-only-control-index.md)。它固定 Loopback/Bearer 激活、最小活跃 Turn/渠道投影、
稳定排序、20/50 分页、opaque cursor、快照降级与原始身份/正文禁令。Fixture consumer 仅验证 Contract 形状和敏感值
禁令；此步没有 Controller、Route、Bean、SQLite 查询、前端或 SSE，不能说成 Dashboard 实现。

### C1：只读控制面索引（已完成）

已在现有 Operator Session 和 Loopback Filter 后增加 `GET /api/v1/control/index`：默认关闭，认证后只投影活动
Turn/渠道健康，不读取历史正文、不访问真实 Telegram、无前端、无写入。实现固定 20/50 分页、内存一次性 actor-bound
opaque cursor、稳定排序、最小字段、快照退化与参数白名单；用 Fake Runtime、固定 Clock、MockMvc 和 C0 Fixture 验证。

### C2：受限历史浏览（C2-A 内存终态目录已完成）

已完成的 C2-A 仅增加基于 Registry 内存终态 Tombstone 的 `GET /api/v1/control/history` 目录；没有正文、
Session/Message/SQLite 查询或详情 Route。它只投影 opaque `historyRef`、channel、终态与完成时间，并复用默认关闭的
Loopback/Bearer/Servlet 门禁。

C2-B 已以 31 Case Fixture 实现严格的 `GET /api/v1/control/history/detail`：它只在当前 Scope 返回零正文的
`USER`/`ASSISTANT` role/time，使用 60 秒一次性 actor/Scope-bound Ref/Cursor，固定 24 小时/1,024 candidate/20 item
预算。Bootstrap 默认 Scope Resolver 拒绝、Snapshot Port fail-closed，SQLite 只在临时 Java 数据库/Fake 验证；因此它不会
自动读取用户、Python 或生产数据。其余 C2 变体仍需单独批准，届时才可讨论数据保留扩展、角色/正文、页大小、全文检索或
持久化绑定。不得把 R11-B4 的模型证据 Tool 或 HTTP Memory API 复用为 Dashboard 越权读取。
详细的决策门、Fixture、TDD 顺序和验收标准见
[R13-C2 受限历史浏览执行计划](2026-07-19-r13-c2-restricted-history-browse-plan.md)。

### C3：逐项受审批的管理写入（待单独批准）

Session/Message/Memory 删除、编辑或批量操作逐项独立进入 R11-B2c 生产 Recovery Router。每个动作都应有
Capability Version、Approval、Capsule、Ledger、Reservation、幂等键、取消/过期/并发/`UNKNOWN`/`COMMIT_UNREPORTED`
Fixture 和可恢复审计。先在临时 Java SQLite/Fake Invoker 演练，禁止真实数据或渠道。

### C4：前端与资产供应链（待解除前端冻结）

只有 C0–C3 的 API 稳定、且用户显式解除前端冻结后，才评估 Java 前端。固定构建输入、内容安全策略、资源完整性、
缓存、错误页、无 JS 降级、可访问性和升级/回退；Plugin 自定义面板必须另有签名/受信资产 Contract，不能加载 Python
目录中的任意 JS/CSS。

### C5：渠道逐一纵向切片（待单独批准）

IPC、QQ、Telegram 扩展和 Plugin Channel 分别编写 Contract。共同要求：身份/Allowlist、稳定 Session Binding、
消息去重、背压、取消、持久 Recovery、关闭、脱敏日志和本地 Fake Transport。真实网络/密钥 Smoke 在每个 Channel 的
离线门禁通过后，另行请求操作授权。

## 5. 每阶段验收与推进条件

- 每个 C 阶段先新增版本化 Fixture，再 RED/GREEN；只运行与改动相关的聚焦测试，C3/C5 完成后才运行默认、`failure`、`compat` 三套全门禁。
- 文档、Capability 矩阵、ADR 和运行手册与实现同一提交更新；不得把 Fixture 或 Fake Transport 写成真实渠道验收。
- C3 依赖用户先选定并批准 R11-B2c 的第一个副作用 Capability；C4 依赖解除前端冻结；C5 每个真实 Channel 都依赖独立网络/密钥授权。

在这些条件满足前，R13 完成 C0、C1、C2-A 与默认拒绝的 C2-B；它不是 Python Dashboard、正文历史浏览、管理写入或真实渠道实现。
