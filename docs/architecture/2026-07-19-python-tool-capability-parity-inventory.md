# 2026-07-19 Python Tool / Java Capability 逐项差距清单

- 状态：当前事实；用于排定 R11–R14，不代表任何未实现 Tool 已获授权
- Python 证据基线：`akashic-agent` 提交 `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Java 证据基线：`agent/r12-skill-catalog`，含 R12-S4 `read_skill` 与 R11-B4 会话证据 Tool
- 关联：[Python/Java 能力差距矩阵](python-java-capability-matrix.md)、[全量对齐计划](../plans/2026-07-18-java-parity-program.md)

## 判定规则

- **已覆盖**：Java 有生产路径和自动化证据，且没有已知会影响用户行为的语义分叉。
- **部分**：同名或相邻能力已存在，但参数、结果、Scope、可见性或运行边界不同。
- **未实现**：没有 Java 模型可调用表面；已有 Runtime/Store 不能代替 Tool。
- **有意替代**：Java 已选择不同且更受限的模型；它只在有明确 Contract/ADR 时可视为对齐替代。

“有 Runtime”不等于“模型能调用”。Tool 还需要稳定 Schema、Catalog 可见性、会话/身份绑定、预算、取消、错误投影、
审计和相应的 Approval/UNKNOWN 语义。

## Python 注册面

Python 的 `register_common_meta_tools` 将 Shell、Web、文件、消息证据、消息推送和文件写入等放入默认 Tool Registry；
Memory Profile 还能按 Engine 动态注册 `memorize`、`forget_memory`、`recall_memory` 及自定义信号 Tool。MCP Provider
还注册 `mcp_add`、`mcp_remove`、`mcp_list`。证据见 `agent/tools/meta/register.py`、
`bootstrap/toolsets/mcp.py` 与 `bootstrap/memory.py`。

Java 则采用静态受信 Catalog、`tool_search` 后当前 Turn 解锁的 Deferred Schema 和默认关闭的每一类 Runtime。这是重要的
安全差异：任何“迁移”必须明确保留 Python 的可见性，或以 Contract 把 Java 的受限可见性记录为替代，不能仅添加同名类。

### 已核查的 Python 注册面

下表来自基线 `b65a543` 的注册源码，而不是对运行中配置的推测。它确保“逐 Tool 对齐”包含条件和动态表面，
不会因能力矩阵只按族归类而漏掉实际可注册的名称。

| 注册点 | 精确 Tool 名称/族 | 注册条件 | 本清单对应行 |
| --- | --- | --- | --- |
| `register_common_meta_tools` | `tool_search`、`shell`、`task_output`、`task_stop`、`web_search`、`web_fetch`、`read_file`、`list_dir`、`fetch_messages`、`search_messages`、`message_push`、`write_file`、`edit_file` | 该注册方法内均为 `always_on` | Catalog、文件/Shell/Web、消息证据、消息推送各行 |
| `CommonMetaToolsetProvider` | `read_image_vision` | 配置 VL Provider/Model | Vision 行 |
| `SchedulerToolsetProvider` | `schedule`、`list_schedules`、`cancel_schedule` | Scheduler Toolset | Scheduler 行 |
| `SpawnToolsetProvider` | `spawn`、`spawn_manage` | `spawn_enabled` | Spawn 行 |
| `MemoryToolsetProvider` | `memorize`、`forget_memory`、`recall_memory`、Engine 自定义名 | Memory Engine `tool_profile` | Memory 三件套、动态 Memory Signal 行 |
| `McpToolsetProvider` 与动态 MCP Wrapper | `mcp_add`、`mcp_remove`、`mcp_list`，以及 Server 投影 Tool | MCP Toolset/运行时 Server | MCP 管理、动态 Wrapper 行 |

在相应 Python Toolset 被装配时，上表大多数 Tool 以 `always_on` 注册；Java 的静态 Catalog 和 Deferred 解锁是有意
安全分叉，而非注册面缺失的证据。

## Tool 对照

| Python Tool/族 | Python 已提交行为 | Java 当前状态 | 判定 | 对齐路径与前置 |
| --- | --- | --- | --- | --- |
| `tool_search` | 关键词索引、风险/来源元数据、动态 Registry 搜索 | R11-B1 `ToolCatalog`、CJK/精确检索、Turn-scoped 解锁 | 部分 | Java 已验证受信静态 Catalog；Python 的动态注册、always-on 与完整 Bundle 策略仍未对齐，后续需结合每个来源 Tool 的 Contract |
| `read_file` / `list_dir` | 普通 Workspace 路径、文本分页、图片/二进制提示和目录 emoji 投影 | R11-B3 显式独立 Root、逐段链接拒绝、严格 UTF-8、固定预算、一层目录 | 有意替代 | Java 安全边界已通过 Fixture；图片、二进制提示、递归和真实 `${agent.workspace}` 不在此替代范围 |
| `write_file` / `edit_file` | 创建/覆盖、精确替换、文件锁、差异预览 | 无 | 未实现 | R11 后续逐 Tool `WRITE` Capability：Workspace Sandbox、备份/回退、TOCTOU、Approval、Capsule/Ledger、`UNKNOWN` 与恢复 Contract |
| `fetch_messages` | 按 ID/source evidence 读取原始消息及有限上下文 | R11-B4 默认关闭的 `fetch_messages`：当前 Session、opaque `msg-v1:<seq>`、窗口、16 条/12k code-point 投影上限 | 有意替代 | 已通过 Fixture、SQLite、Tool Loop、Bootstrap 和三套门禁。Java 不接受 `source_ref`/原始 ID、不暴露 Session/Route、不能跨 Session，宽窗口安全失败而不静默截断 |
| `search_messages` | 当前会话全文/关键词消息检索 | R11-B4 默认关闭的 `search_messages`：当前 Session、Unicode 空白分词、`Locale.ROOT` 小写 OR 匹配、角色/分页与 50 行预览 | 有意替代 | 已通过 Fixture、SQLite、Tool Loop、Bootstrap 和三套门禁。Java 不建 FTS 或跨 Channel 枚举；预览不是直接证据，模型须再调用 `fetch_messages` |
| `recall_memory` | 语义/关键词/时间线检索、种类/时间筛选、证据/引用投影 | R12-S5 已实现默认关闭的当前 Scope cosine/Hotness `recall_memory`；无 Keyword/RRF、时间线或证据字段 | 有意替代 | Tool 仅使用 ChatService 私有 SHA-256 Scope Binding、严格 Java 大写 `MemoryType`、有界正文与 Deferred Schema；不能复用只接受安全 HTTP ID 的 Memory 管理 API。Java 原生记忆没有 Python `memory2` 的 evidence/source-ref/activation 元数据，不能声称等价 |
| `memorize` | 由 Engine Profile 决定的记忆写入与类型 | Java 有显式 HTTP Write API，不是 Tool | 未实现 | `WRITE` Capability；先选择是否允许模型摘要持久化、Embedding 费用、Scope/类型、Approval 与恢复语义 |
| `forget_memory` | 批量去重后软失效，返回命中/缺失和条目 | R11-B2c 已实现当前 Scope 批量软失效、无正文安全结果、Approval/Capsule/Reservation/Recovery 与默认关闭 Loopback 控制路径；尚未注册 Tool/Chat 生产器 | 部分 | 用户已批准 Scope 与无正文差异；下一步是单独冻结 Tool/Chat Pending 生产 Contract，不能把本地 Resume 路由当作模型可调用 Tool |
| `message_push` | 向已注册渠道发送文本/文件/图片 | Telegram 有渠道投递，但无模型 Tool | 未实现 | `EXTERNAL_SIDE_EFFECT` Capability；真实渠道、目标身份、内容/附件 Root、Receipt、Outbox、Approval、UNKNOWN 和真实 Smoke 均需独立范围 |
| `schedule` / `list_schedules` / `cancel_schedule` | 创建、列举、取消 AT/AFTER/EVERY/cron 任务，可能触发主动投递 | R8 SQLite Scheduler 有受限 AT/EVERY、租约与 NoOp Delivery；默认关闭的 Deferred `list_local_proactive_jobs` 只检视活动 Job 安全摘要 | 有意替代 | `list_local_proactive_jobs` 已由 13 Case Fixture 与三套门禁验证，但不等价 Python `list_schedules`：无渠道/聊天身份、正文、时区、运行次数、hash/key 或数据库路径。`schedule`/`cancel_schedule` 仍为 `WRITE`，投递与 `message_push` 必须分离授权 |
| `shell` / `task_output` / `task_stop` | 前台/后台进程、日志轮询、超时、杀进程树 | 无 Shell Tool；R8 Subagent 不继承 Tool/网络 | 未实现 | 高风险 Sandbox Capability：命令 allowlist、工作目录、资源、PTY、进程树、日志脱敏、Approval、UNKNOWN 与恢复，不能复用普通 Subagent |
| `spawn` / `spawn_manage` | 模型创建/查看/取消带 profile 的后台 Python Subagent | R8 有受限 parent-bound Subagent Runtime；无 Tool | 部分 | 先冻结任务内容最小化、profile 映射、身份、结果回灌、取消与并发；不要把 Python 的 `scripting/general` 权限隐式授予 Java Runtime |
| `web_search` / `web_fetch` | 外部网络搜索与抓取 | 无 | 未实现 | 网络 Capability：允许域名、重定向、DNS/私网防护、配额、缓存、正文净化、审计、费用及真实网络授权 |
| `read_image_vision` | 读取图片并调用视觉 Provider | 无 | 未实现 | 需文件 Root 与多模态 Provider 双 Contract；图片解码、尺寸/字节预算、Provider 费用/数据流与结果净化不可用文件 Tool 代替 |
| `mcp_add` / `mcp_remove` / `mcp_list` | 模型动态启动/移除 stdio MCP Server | R5.1 仅静态本地 stdio；无管理 Tool | 有意替代 | Java 的静态受控 Server 是既有安全选择。动态进程/环境/命令管理属于高风险新能力，不能用 R5.1 或 R12-S2 Assets 目录推定授权 |
| 动态 MCP Wrapper | Runtime 新 Tool 立即进入 Python Registry | Java 静态发现、投影与 Catalog；默认关闭 | 部分 | 静态 Read-only Tool 主线已有证据；动态注册、远程 Transport、OAuth 和副作用 MCP Tool 仍未对齐 |
| 动态 Memory Signal Tool | Engine Profile 自定义名字/Schema/风险的 Tool | 无 | 未实现 | 只有先定义受信 Capability Manifest、版本/签名、Schema/风险验证和可撤销装配，才可迁移；不得由 Plugin/Python 运行时任意注入 |

## 与计划的依赖顺序

1. **R11-B4 已完成。** 当前 Session 的受限只读 `fetch_messages`/`search_messages` 已由 Contract/ADR/26 Case Fixture
   固定，并通过默认、`failure`、`compat` 门禁；它不授予跨会话读取、Memory Tool 或任何写入。
2. **R11-B2c：接入受控 Tool/Chat Pending 生产器。** 默认关闭的 Capability 与 Recovery Router 已完成；下一步必须先
   冻结 Catalog 可见性、Chat Pending 投影和“创建不执行”Contract，不能令通用 Tool Loop 同步批准或执行。
3. **Skill 不构成独立执行 Capability。** Python `SkillsLoader` 只读取 Markdown 并将其注入 Prompt；模型随后调用的
   Shell、文件、MCP、消息、调度或 Spawn Tool 仍是本表中各自的能力。ADR-0029 因此不迁移 Skill Runner，也不允许
   `SKILL.md`、`scripts/` 或 `references/` 绕过 Tool Catalog、审批、Sandbox 或 `UNKNOWN`。
4. **R12-S5：Memory Tool 受限替代（完成）。** `recall_memory` 只读取当前 Java Native Scope，并不等价于 Python
   的 Keyword/RRF、时间线和 citation evidence；`memorize`/`forget_memory` 仍须逐 Tool 副作用 Contract。
5. **R8 已完成 Scheduler 安全只读检视；后续再接线写入。** `list_local_proactive_jobs` 只读替代已完成；创建/取消、Subagent Tool 与所有实际 Delivery 继续各自冻结并须单独 Contract。
6. **R13/R14：Channel Push、Web、Vision、Shell、动态 MCP 与动态 Tool。** 按网络、外部副作用、进程、数据泄露等
   风险维度逐个 Contract，不能以 Python Registry 的“默认可见”作为 Java 实现依据。

## 立即禁止的错误归因

- 不把 Java R12-S2 Resources/Prompts Assets Catalog 记为 Python MCP 对齐；Python 基线没有这个表面。
- 不把 Java HTTP Memory API 或 Scheduler/Subagent Runtime 记为模型 Tool 对齐。
- 不把 Java 的显式 Memory HTTP 管理 API 当作 Telegram 或任意渠道的 Memory Scope Resolver：它有意拒绝
  `telegram:<chatId>` 等非安全路径标识；自动检索的私有 SHA-256 Binding 才是 Chat Tool 可用的 Scope 边界。
- 不把 `read_file`/`list_dir` 的独立 Root 安全替代记为 Python 任意 Workspace 文件能力。
- 不把 Fake Capability 演练、Approval Inbox 或 Pending Store 记为“已经能执行副作用”。
- 不把真实 Telegram/Provider/MCP/Workspace 的 Smoke 或生产启用归入本清单；它们另需运行授权。
