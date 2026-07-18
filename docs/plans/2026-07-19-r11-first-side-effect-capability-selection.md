# R11-B2c 首个副作用 Capability 候选、语义差异与授权边界

- 状态：候选已审计；Python/Java 删除语义存在实质差异，**未选择实现路径、未获逐工具执行授权**，零生产路由、零 Capability、零 Tool 注册
- 日期：2026-07-19
- 前置：[Pending Operation Session Anchor 与 Recovery Capability 契约](../contracts/pending-operation-recovery-capability.md)
- 关联：[R11 O6 Pending Recovery Capability 实施计划](2026-07-19-r11-pending-recovery-capability-implementation.md)

## 结论：不能把 Java 现有删除 API 直接称为 Python 对齐

`forget_memory` 仍是最小的本地副作用候选，但已提交 Python 基线与现有 Java 原生记忆 API 的语义并不相同：

| 维度 | Python `ForgetMemoryTool` | 现有 Java 原生记忆 API |
| --- | --- | --- |
| 参数 | `ids` 数组；按输入顺序去空白、去重 | 单个 `memoryId` 与 HTTP 幂等键 |
| 作用域 | 直接按 ID 查询；Tool 本身不附加 Session Scope | 精确当前 Session Scope |
| 写入 | 标记已有项为 `superseded` | 物理删除正文和向量 |
| 成功投影 | `requested_ids`、`superseded_ids`、`missing_ids`、命中 `items` | `DELETED`/`NOT_FOUND` 与 ID；不返回正文 |
| 隐私含义 | 结果可能回传被标记项的内容 | 结果刻意不回传内容 |

因此“当前 Scope 的单 ID 物理删除”只能是一个新的 Java 安全替代方案，不能在没有明确批准的情况下冒充
`forget_memory` 的兼容迁移。此前“旧 Python `memory2.db` 可丢弃”的决定只免除了旧数据迁移，不自动批准改变
运行时的删除、Scope 或结果语义。

删除或失效用户记忆仍是副作用。它不能因为已有 SQLite Store、Fake Capability 或全局“重写 Python Agent”目标
而被视为已获执行授权；在用户明确批准下面的一条准确路径、风险和边界前，系统必须继续保持生产 Deny All、无
Pending Recovery 路由、无 Worker、无 `forget_memory` Tool。

## 已提交 Python 基线与候选筛选

| Python Tool | 副作用/依赖 | 当前 Java 基础 | 本轮结论 |
| --- | --- | --- | --- |
| `forget_memory` | 批量软失效既有记忆 | Java 原生记忆已有 Scope 绑定、物理删除和幂等 Mutation Ledger | 需要在下方两条路径中选择；不能缩为单 ID 后声称兼容 |
| `memorize` | 持久化正文并生成 Embedding | Java Memory Write API 已有实现 | 暂缓；可能触发 Embedding Provider/费用，且将模型摘要持久化 |
| `schedule` / `cancel_schedule` | 耐久任务，后续可触发主动投递 | R8 Scheduler 已受限实现 | 暂缓；与真实消息投递和主动运行时耦合 |
| `message_push` | 向渠道发送文本/文件/图片 | Telegram 离线投递基础 | 冻结；需要真实渠道、身份和外部副作用授权 |
| `write_file` / `edit_file` | Workspace 写入 | 仅有独立 Root 只读 Tool | 冻结；需要工作区沙箱、备份和回退 Contract |
| `shell` / `spawn` | 进程执行与子任务 | R8 Subagent 仍无 Tool/网络继承 | 冻结；需要命令沙箱、资源控制和审计 Contract |
| `web_fetch` / `web_search` / vision | 远程网络或 Provider | 无获批真实网络路径 | 冻结；需要网络、费用和隐私 Contract |

## 可选择的实现路径（仅供授权审阅）

### 路径 A：Python 语义对齐

新增 Java 原生记忆的 `SUPERSEDED` 状态、批量软失效 Mutation、适当的 History/Query 过滤和版本化迁移；Tool 接受
`ids` 数组，保留去空白、稳定去重、请求/命中/缺失的顺序语义。结果必须重新设计为不会因回送 `items` 泄漏正文，
或由新的兼容 Contract 明确批准该安全差异。还必须明确 Python Tool 目前无 Scope 限制这一风险是否在 Java 中保留：
若维持 Java 的当前 Session Scope，仍是受控安全差异，不能称逐字节兼容。

这条路径最接近 Python 行为，但数据模型、检索语义和 Fixture 的变更较大。

### 路径 B：Java 安全替代

保持当前 Java 原生记忆的当前 Session Scope 与物理删除，并定义一个新 Tool（建议 `delete_memory`，避免复用
`forget_memory` 制造兼容错觉）。可限制为单 ID 或在同一 Scope 中支持有界批量 ID；结果只暴露稳定状态和 opaque ID。
这条路径不访问网络、不启动进程、不读取或写入 Workspace 文件，也不碰已明确弃用的 Python `memory2.db`，但必须
作为用户批准的替代方案记录在能力矩阵中。

## 路径 B 的拟议 Capability V1

1. Tool 名称固定为 `delete_memory`，版本固定为 `java-memory-delete-v1`，风险为 `WRITE`；不能由模型、Plugin、MCP
   或配置字符串动态创建。
2. 输入只接受严格 `memoryId`；它是当前 Java Session Scope 的既有 opaque ID。V1 不接受批量 ID、Session、路径、
   Scope、正文、Embedding、`requestId` 或调用者给出的幂等键。
3. Capability 只在 `agent.memory.mode=JAVA_NATIVE`、Loopback 监听、独立显式 `agent.capabilities.memory-forget.mode`
   和既有 Pending Recovery Store 全部就绪时注册；任一条件不满足即不在 Catalog 出现。
4. 每次调用都先创建加密 Capsule、Approval Inbox、Pending Operation 与 Session Anchor；没有 `APPROVED` 决定时
   Invoker 次数为零。Resume 只接受不透明 Operation Ref，并复用现有 Loopback 认证边界。
5. 获批后先原子获得唯一 Reservation，写入 `RUNNING`，再调用一个只会执行当前 Scope 单 ID 删除的 Invoker。
   删除前的取消、过期、Cursor 变化、绑定不符、`UNKNOWN` 或 `COMMIT_UNREPORTED` 都不得重试或再次删除。
6. 成功结果只投影稳定 `DELETED` 或 `NOT_FOUND` 与 opaque ID；不回送被删正文、Embedding、Hash、Scope、Session、
   Capsule、Approval、Ledger 或异常正文。Conversation CAS 失败只转 `COMMIT_UNREPORTED`，绝不重放删除。
7. 默认部署继续为 `DISABLED`。所有 RED/GREEN 只使用临时 Java SQLite、Fake Approval、Fake Invoker 和固定时钟；
   不访问真实用户数据、Python Workspace、网络、Provider 或 Telegram。

## 获授权后的连续 TDD 顺序

1. **F1 Contract Fixture（RED）。** 按选定路径冻结 Capability 名称/版本、批量或单 ID 参数、模式组合、脱敏结果、默认零注册、
   Anchor/Approval/Reservation/取消/过期/并发/`UNKNOWN`/`COMMIT_UNREPORTED` 语义。
2. **F2 Capability Kernel（GREEN）。** 引入不可变、静态 Capability Descriptor 与所选删除/软失效 Invoker Port；
   无 JDBC、Spring 或 Tool Registry 依赖。
3. **F3 Production Recovery Router（GREEN）。** 只在明确 Mode 和 Loopback 认证下装配 Pending Store、Anchor Port、
   Capability Resolver 与 Resume/Cancel/Status Controller；其他部署零路由。
4. **F4 Java Memory Adapter（GREEN）。** 路径 A 实现 Soft-Supersede Store/Invoker；路径 B 将精确 Scope 的
   `MemoryDeleteService` 包装为 Invoker。两者都验证 Capsule 绑定与安全 Result；不为测试或运行时创建 Python
   Memory 文件。
5. **F5 纵向失败矩阵（GREEN）。** 用真实临时 SQLite 验证单获胜者、撤销/过期/新 Turn 零副作用、Commit Failure
   零重放以及关闭/中断；随后运行默认、`failure`、`compat` 三套完整门禁。
6. **F6 文档与审查。** 回写 Contract、Capability 审计、Roadmap、矩阵和运行手册；仍不进行真实数据 Smoke，除非
   另有运行授权。

## 需要的明确决策

继续实现 F1–F6 前，需要用户确认以下之一：

1. **路径 A**：以 Python `forget_memory` 的批量软失效语义为基准迁移，并单独确认 Scope 与结果脱敏的安全差异；或
2. **路径 B**：批准 `delete_memory` 作为 Java 专有替代，范围仅限 Java 原生记忆库中当前 Session 的物理删除，默认关闭、
   Loopback 认证、人工审批与本地 Fake 测试，不启用真实数据、网络、Workspace、Telegram 或 Python 记忆访问。
