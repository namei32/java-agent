# Tool 审批、副作用、幂等与沙箱安全契约

- 状态：草案
- 候选契约版本：1
- 日期：2026-07-14
- 前置契约：[核心消息、生命周期与 Tool 契约](core-message-lifecycle-tool.md)
- 前置契约：[Tool Runtime 安全契约](tool-runtime-safety.md)
- 适用阶段：R3.2 Tool Approval Framework
- Python 参考 Commit：`b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`

> 本文尚未批准，不能作为开放副作用工具的授权。即使本文获批，候选版本 1 也只授权实现默认拒绝的审批与幂等框架、测试工具和安全边界接口；文件写入、Shell、Web 写入、消息发送、记忆修改和外部系统变更仍需各自独立的 Tool Capability Contract。

## 1. 目的

本契约定义模型提出副作用 Tool Call 后，Java Runtime 在真实执行前必须满足的风险分类、审批绑定、整批预检、一次性消费、幂等记录、失败恢复、生命周期、审计和沙箱边界。

目标不是让“用户点了同意”成为无限权限，而是确保一次批准只能授权一个内容固定、时间有限、可审计且不会被自动重复执行的具体操作。

## 2. 决策输入与批准的安全差异

Python 基准已有以下可复用投影：

- Tool Registry 使用 `read-only`、`write`、`external-side-effect` 风险标签。
- `pre_tool_use` Hook 可以修改参数或返回 `denied`，真实 Invoker 在 Hook 通过后才执行。
- Tool 执行结果区分 `success`、`denied` 和 `error`。
- Shell Safety Plugin 能基于命令模式拒绝部分操作。

Python 当前实现不足以成为 Java 的安全实现基准：

- 没有绑定具体 Arguments Hash、Turn、Call ID 和过期时间的人类审批记录。
- Hook 可以修改参数；批准前后参数是否相同没有不可变证明。
- 没有持久化幂等 Ledger，也没有崩溃窗口的 `UNKNOWN` 状态。
- 风险字符串存在 `write`、`read-write`、`external-side-effect`、`destructive` 等不一致取值。
- Shell 黑名单和若干模式判断不能证明进程、文件、网络和凭据边界安全。
- Python 错误与拒绝正文可能包含 Hook 或异常细节，不满足 Java 当前的安全错误规则。

因此 Java 采用批准的安全差异：保留共同风险与 `DENIED` 投影，但新增严格枚举、不可变请求指纹、一次性审批、持久化前置条件、默认拒绝和逐工具沙箱契约。不得为追求逐行兼容而移除这些保护。

## 3. 范围

候选版本 1 包含：

- `READ_ONLY`、`WRITE`、`EXTERNAL_SIDE_EFFECT` 三种 Tool 风险。
- `APPROVAL_REQUIRED` 工具运行模式的协议定义。
- 审批请求、决定、过期、取消和一次性消费。
- 混合 Tool Call 批次的整批预检与审批。
- Side Effect 幂等键、Execution Ledger 和崩溃未知状态。
- 安全生命周期、审计投影和固定公开错误。
- 文件、Shell、Web/消息工具在未来启用前必须满足的沙箱门禁。
- 仅测试可见的 Fake Side Effect Tool。

候选版本 1 不包含：

- 真实文件写入、Shell、Web 写入、消息发送或记忆修改工具。
- 审批 HTTP API、Dashboard、CLI、Telegram 或其他人类交互界面。
- 挂起 Turn 的持久化、跨进程恢复和审批后异步续跑。
- 自动批准、批量长期授权、“本次会话始终允许”或通配符授权。
- Tool 并行、自动重试、补偿工作流或分布式锁。
- 把 Spring AI、MCP、Plugin 或 Provider SDK 变成审批或执行控制者。

## 4. 固定安全不变量

1. 模型不能批准自己的调用，也不能提供可信的 Approval ID、风险等级、摘要或幂等键。
2. `WRITE` 和 `EXTERNAL_SIDE_EFFECT` 在真实执行前必须得到当前操作的一次性批准。
3. 批准必须绑定确切 Session、Turn、Call ID、Tool、Tool 版本、风险、规范化 Arguments Hash、幂等键和过期时间。
4. 批准后 Arguments、Tool 版本、风险或执行边界发生任何变化，都必须重新审批。
5. 未批准、拒绝、过期、取消、审批服务异常或 Ledger 异常时，真实 Invoker 的调用次数必须为零。
6. Side Effect 不自动重试；具有相同幂等键的重放不得再次执行。
7. 无法证明操作成功或未发生时必须进入 `UNKNOWN`，不得猜测成功、失败或自动补偿。
8. 审批只回答“是否允许这个操作”，不能绕过 Schema、预算、超时、并发许可、沙箱或具体工具领域校验。
9. 每个真实副作用工具必须有独立 Tool Capability Contract；风险标签和审批不能替代最小权限设计。
10. 生产 Bootstrap 在没有可信 Approval Port、Durable Ledger 和具体 Tool Contract 时必须保持 Fail Closed。

## 5. 风险模型与运行模式

### 5.1 风险等级

| 风险 | 定义 | 审批要求 |
| --- | --- | --- |
| `READ_ONLY` | 不修改 Workspace、进程、网络远端、消息、记忆或外部状态 | 沿用 R3.1，无审批 |
| `WRITE` | 只修改经契约批准的隔离 Java Workspace 内状态，不访问外部系统 | 每次调用审批 |
| `EXTERNAL_SIDE_EFFECT` | 修改进程、操作系统、网络远端、消息接收方或其他外部系统 | 每次调用审批，且需要更严格 Tool Contract |

注册时风险是最低风险。Runtime Policy 可以根据参数、来源或执行边界提升风险，但绝不能降低注册风险。风险只能由项目代码和配置决定，不读取模型提供的同名字段。

Shell、任意命令执行、外部消息发送和网络写入在没有更细分类前一律属于 `EXTERNAL_SIDE_EFFECT`。

### 5.2 `agent.tools.mode`

候选版本 1 提议把运行模式扩展为：

| 值 | 可见与执行行为 |
| --- | --- |
| `DISABLED` | 不发送任何 Tool Definition |
| `READ_ONLY` | 只注册并发送 `READ_ONLY` Tool |
| `APPROVAL_REQUIRED` | 可发送已批准注册的三类 Tool；副作用调用必须经过本契约 Gate |

保持现有兼容默认值 `READ_ONLY`，但部署模板继续显式使用 `DISABLED`。`APPROVAL_REQUIRED` 必须由部署显式设置；升级不得自动切换。

在本契约获批并完成全部实现门禁前，应用仍不得接受 `APPROVAL_REQUIRED`。

## 6. 审批请求与决定

### 6.1 `ApprovalRequest`

Runtime 在 Schema、预算和工具领域预检通过后生成不可变请求：

| 字段 | 规则 |
| --- | --- |
| `approvalId` | Runtime 生成的至少 128 bit 不透明随机 ID |
| `turnId` | Runtime 生成，绑定当前 Session 中的当前 Turn |
| `callId` | Provider Tool Call ID |
| `toolName` | 注册表中的精确名称 |
| `toolVersion` | Tool Contract/实现版本，非模型字段 |
| `risk` | Runtime 计算的有效风险 |
| `argumentsHash` | 规范化 Arguments 的 SHA-256，不含原文 |
| `idempotencyKey` | Runtime 生成的当前逻辑操作键 |
| `summary` | Tool 生成的最小化安全预览，仅供审批者查看 |
| `issuedAt` / `expiresAt` | 使用注入 Clock；默认有效期候选值为 5 分钟 |
| `fingerprintVersion` | 固定为 `approval-fingerprint-v1` |
| `fingerprint` | 对全部绑定字段执行的 SHA-256 |

`summary` 不能由模型直接提供，不能作为执行参数，也不能写入普通日志、生命周期或会话消息。具体 Tool Contract 必须定义摘要允许出现的字段和脱敏规则。

### 6.2 `ApprovalDecision`

决定只能来自可信 `ApprovalPort`：

- `APPROVED`
- `DENIED`
- `EXPIRED`
- `CANCELLED`

决定必须回传 `approvalId`、`fingerprint`、决定时间和可信审批主体引用。Runtime 必须重新检查所有绑定字段、过期时间、Turn 取消状态和 Ledger 状态。

审批主体的自由文本备注不能回送模型，不能参与执行，也不能进入安全日志。

## 7. Arguments 规范化与指纹绑定

候选版本 1 使用项目自有、版本化的规范化 JSON：

- UTF-8 编码。
- Object Key 按 Unicode Code Point 排序。
- Array 保持原顺序。
- String 保持精确值，仅做 JSON 必需转义。
- Integer 不允许前导零；Number 使用无歧义十进制规范表示。
- 拒绝重复字段、非有限数字和不能稳定表示的值。
- 不在规范化过程中应用 Tool 默认值或修改参数。

Fingerprint 使用长度前缀字段编码，至少覆盖：版本、Session Binding、Turn ID、Call ID、Tool 名称、Tool 版本、有效风险、Arguments Hash、Idempotency Key、签发与过期时间。禁止使用字符串简单拼接。

Tool Hook 或 Policy 如需改参，只能发生在生成 Approval Request 之前；批准后任何改参都使批准失效。

## 8. 审批状态机与并发

审批状态固定为：

```text
PENDING -> APPROVED -> CONSUMED
       -> DENIED
       -> EXPIRED
       -> CANCELLED
```

规则：

- `PENDING` 只能完成一次终态决定。
- `APPROVED` 只有在执行前全部复检通过时才能原子变为 `CONSUMED`。
- 相同 Approval ID 的并发消费最多一个成功。
- `DENIED`、`EXPIRED`、`CANCELLED` 和 `CONSUMED` 都不可恢复为可执行状态。
- Approval ID 或 Fingerprint 不匹配按安全失败处理，不向调用方区分不存在与不匹配。
- Turn 取消优先于迟到的批准。
- 批准到达时如果 Session 已进入新的 Turn，旧批准失效。

候选版本 1 不实现跨请求挂起和恢复。生产 `ApprovalPort` 必须默认拒绝；真实人类审批渠道必须在后续独立 HTTP/Channel Contract 中解决身份认证、Pending Turn 持久化和恢复语义。

## 9. Tool Call 批次语义

模型返回的整批调用按以下顺序处理：

1. 完成现有结构、预算、Schema、风险和具体工具领域预检。
2. 对所有副作用调用生成 Approval Request；此时任何 Tool Invoker 调用次数都为零。
3. 收集并验证全部决定。
4. 如果任一副作用调用未获批准，整批零执行：对应调用返回 `DENIED` 或 `CANCELLED`，其余调用返回 `SKIPPED`。
5. 只有全部副作用调用均获批准，才按模型顺序串行执行整批调用。
6. 每个批准在对应调用执行前单独消费；过期或复检失败时停止批次。
7. 一个副作用调用出现非 `SUCCESS` 后，后续调用不执行并产生 `SKIPPED`。

“整批零执行”只覆盖审批前原子性。真实 Side Effect 开始后不承诺跨多个工具回滚；因此执行中失败时必须停止后续调用，不能声称整批事务成功。

## 10. 幂等键与 Execution Ledger

### 10.1 幂等键

- Runtime 在首次接受逻辑操作时生成不可预测的幂等键。
- 同一操作的恢复或重放必须复用原键；新的用户意图或新的 Turn 使用新键。
- Provider Call ID、Tool 名称或 Arguments Hash 不能单独作为幂等键。
- 模型、Tool Arguments 和 HTTP 客户端不能覆盖 Runtime 幂等键。
- 外部服务支持 Idempotency Key 时，具体 Adapter 必须传递 Runtime Key 的安全派生值。

### 10.2 Ledger 状态

| 状态 | 含义 | 重放行为 |
| --- | --- | --- |
| `RESERVED` | 审批已消费，副作用尚未开始 | 不重复执行；仅允许同一受控执行者继续 |
| `RUNNING` | 已进入真实副作用边界 | 不重复执行 |
| `SUCCEEDED` | 已确认成功并保存安全结果 | 返回已保存结果，不调用 Invoker |
| `FAILED` | 已证明没有产生外部变更或失败状态确定 | 返回已保存安全失败，不自动重试 |
| `UNKNOWN` | 无法证明成功或失败 | 终止 Turn，不自动重试或补偿 |

执行前必须先持久化 `RESERVED/RUNNING`。真实副作用成功后再持久化 `SUCCEEDED`。如果副作用可能已经发生但成功记录失败，必须写入或恢复为 `UNKNOWN`。

生产启用任何 Side Effect 前必须有 Durable Ledger。内存 Ledger 只允许测试使用。Ledger 写入失败时 Fail Closed，Conversation 消息不得提交。

## 11. 失败、结果与会话提交

固定安全结果：

| 场景 | Tool Result | 安全正文 | Turn 行为 |
| --- | --- | --- | --- |
| 明确拒绝 | `DENIED` | `工具调用未获批准。` | 可回送模型生成最终说明 |
| 审批过期 | `DENIED` | `工具审批已过期。` | 可回送模型生成最终说明 |
| 批次中未执行 | `SKIPPED` | `工具调用已跳过。` | 随同批结果回送模型 |
| Turn 取消 | `CANCELLED` | `工具调用已取消。` | 不再调用模型，不提交 |
| 审批服务或 Ledger 不可用 | 无 | 稳定公开错误 | `TURN_FAILED=APPROVAL_UNAVAILABLE` |
| 状态未知 | 无 | 稳定公开错误 | `TURN_FAILED=SIDE_EFFECT_STATE_UNKNOWN` |

审批拒绝不是系统错误。模型可以在收到安全 `DENIED/SKIPPED` 结果后给出最终文本，最终 User/Assistant 仍可提交。

副作用成功后模型或 Conversation SQLite 提交失败时：

- 不回滚或重复真实 Side Effect。
- Conversation 当前轮次不提交。
- Approval/Audit/Ledger 记录必须保留。
- 同一幂等键的恢复不得再次执行，需由后续恢复契约决定如何向用户呈现结果。

## 12. 生命周期与审计

候选版本 1 提议新增：

- `APPROVAL_REQUESTED`
- `APPROVAL_RESOLVED`
- `SIDE_EFFECT_STARTED`
- `SIDE_EFFECT_COMPLETED`

推荐顺序：

```text
TOOL_CALL_STARTED
  -> APPROVAL_REQUESTED
  -> APPROVAL_RESOLVED(APPROVED|DENIED|EXPIRED|CANCELLED)
  -> SIDE_EFFECT_STARTED（仅批准后）
  -> SIDE_EFFECT_COMPLETED(SUCCESS|ERROR|TIMEOUT|UNKNOWN)
  -> TOOL_CALL_COMPLETED
```

`TOOL_CALL_STARTED` 表示 Runtime 开始处理调用，不证明真实副作用已经发生；只有 `SIDE_EFFECT_STARTED` 表示越过副作用边界。

生命周期和普通日志继续禁止包含 Arguments、Result、Summary、审批备注、真实 Session、Actor 原文、密钥、路径原文、消息正文或异常正文。

Durable Audit 只允许保存：Approval ID、Turn ID、Call ID、Tool 名称/版本、风险、Arguments Hash、Idempotency Key Hash、Decision、Actor Hash、时间、Execution 状态和稳定错误码。Audit 不是 Conversation Transcript，失败 Turn 也不能删除。

## 13. 沙箱与最小权限门禁

审批不能扩大 Tool 注册时声明的执行边界。每个 Tool 必须在注册元数据中绑定不可变 `ExecutionBoundary`。

### 13.1 Workspace 文件写入

未来文件写入 Tool 至少必须：

- 只允许独立 Java Sandbox Root，不得默认指向真实 Python Workspace。
- 规范化绝对路径并验证每一级父目录；拒绝 `..`、符号链接逃逸和边界外目标。
- 对不能证明安全的挂载点、链接类型或平台能力 Fail Closed。
- 使用临时文件、刷新和同目录原子替换；定义覆盖、权限、大小和备份规则。
- 绑定审批时展示的相对路径、操作类型和大小上限；批准后路径变化必须失效。
- 第一次写入真实 Workspace 副本前停止其他写入者并备份 SQLite/WAL/SHM 与相关文件。

### 13.2 Shell

候选版本 1 不授权 Shell。未来 Contract 至少必须：

- 优先使用参数数组和可执行文件 Allowlist，不接受任意 Shell 字符串。
- 禁止 Shell Interpreter、命令替换、管道、重定向和动态代码执行，除非更高版本逐项批准。
- 使用最小环境变量、固定工作目录、无网络默认、独立进程组、硬 Deadline 和进程树清理。
- 限制 stdout/stderr 字节数，不持久化包含密钥或用户数据的临时日志。
- 禁止后台任务，直到租约、重启恢复、停止和审计 Contract 独立批准。

黑名单或用户批准不能替代上述边界。

### 13.3 Web 写入与消息发送

候选版本 1 不授权 Web 写入或消息发送。未来 Contract 至少必须：

- 绑定 HTTPS Origin、方法、路径模板、接收方和请求体 Hash。
- 默认拒绝私网、Loopback、Link-local、Metadata 地址和 DNS Rebinding。
- 跨 Origin 重定向必须重新校验或拒绝。
- 请求与响应有字节、时间和重定向上限。
- Secret 由受信 Adapter 注入，不能来自 Tool Arguments 或模型。
- 使用外部系统 Idempotency Key；不支持时明确 `UNKNOWN` 与人工核查流程。

## 14. Approval Channel 与身份边界

当前同步 Chat HTTP 没有身份认证，也不能安全持有请求等待数分钟。候选版本 1 不通过延长 HTTP Timeout 模拟审批。

真实人类审批前必须另行批准：

- Approval Inbox/API 的认证、授权、CSRF 和本地/远端暴露范围。
- Pending Turn、完整执行参数和审批摘要的加密持久化。
- 释放 Session Gate 后的顺序、冲突和恢复。
- 审批通知、超时、撤回和迟到决定。
- 应用重启、重复提交和多进程竞争。

在此之前生产 `ApprovalPort` 必须为 Deny All，测试可使用确定性 Fake。

## 15. Golden 与测试要求

必须扩展 Tool Golden，至少覆盖：

- `READ_ONLY` 无审批执行。
- `WRITE` 未审批零执行。
- 批准绑定正确参数后只执行一次。
- 批准后改参、改 Tool 版本、改风险或换 Turn 均拒绝。
- 拒绝、过期、取消和 Approval Port 异常。
- 混合批次任一未批准时整批零执行。
- 同一批准并发消费只有一个成功。
- 同一幂等键重放返回已存结果且 Invoker 次数不增加。
- `RUNNING/UNKNOWN` 不自动重试。
- 副作用成功后模型或 Conversation 提交失败，Ledger/Audit 保留且消息不提交。
- Lifecycle 顺序与敏感字段零泄漏。
- `DISABLED`、`READ_ONLY`、`APPROVAL_REQUIRED` 的 Tool Definition 可见性。

测试使用 Fake Approval Port、Fake Ledger、Fake Side Effect Tool、注入 Clock 和确定性 ID Generator；禁止访问真实文件、Shell、网络、消息系统或真实 Workspace。

## 16. 发布与回退门禁

R3.2 Framework 合并门禁：

```bash
./mvnw spotless:check
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

同时必须满足：

- 生产注册表没有 `WRITE` 或 `EXTERNAL_SIDE_EFFECT` Tool。
- 生产 Approval Port 为 Deny All。
- `.env.example` 和部署继续为 `AGENT_TOOL_MODE=DISABLED`。
- Secret、Workspace、SQLite 和禁止依赖扫描零命中。
- 没有 Approval HTTP/Channel 入口或新数据库 Schema。

Framework 回退只需移除 `APPROVAL_REQUIRED` 解析与相关装配；不得删除已经存在的 Audit/Ledger 记录。真实 Tool 的回退必须由其 Capability Contract 单独定义。

## 17. 批准前置项

批准本契约前必须明确确认：

1. R3.2 只实现默认拒绝框架，不实现真实副作用工具。
2. 是否接受新增四类 Lifecycle Event，并同步把核心 Tool Contract 升级为版本 2。
3. 是否接受 `APPROVAL_REQUIRED` 新模式且保持兼容默认 `READ_ONLY`、部署显式 `DISABLED`。
4. 是否接受混合批次“任一未批准则整批零执行”。
5. 是否接受 Side Effect 成功但后续 Turn 失败时 Conversation 不提交、Ledger/Audit 保留。
6. Durable Ledger 与真实 Approval Channel 延后，因而生产始终 Deny All。

## 18. 变更审批

以下变化必须修改本契约并重新批准：

- 自动批准、长期授权、通配符授权或批准复用。
- 更改风险等级、批次零执行、指纹或一次性消费语义。
- 允许任何自动重试、补偿、并行 Side Effect 或后台任务。
- 允许非 Durable Ledger 的生产执行。
- 新增 Approval HTTP/Channel、Pending Turn 持久化或跨进程恢复。
- 开放任何具体文件、Shell、Web、消息、记忆或外部系统写入工具。
- 在日志、生命周期、Conversation 或模型上下文中暴露审批敏感字段。
