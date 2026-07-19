# 2026-07-19 Akashic / Java 完成度审计

- 状态：当前事实；后续实现必须以本审计和各阶段 Contract 为准
- Python 证据基线：`akashic-agent` 提交 `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Java 证据基线：`agent/r12-skill-catalog`（R12-S5 当前 Scope `recall_memory`、R12-S4 `read_skill`、R11-B4 会话证据 Tool 与 R14-P0/P1 离线边界/决策 Fixture）
- 审计日期：2026-07-19

## 审计边界

Python 工作树目前含未提交的 `infra/channels/telegram_channel.py`、`requirements.txt`、两项测试与
`_handbook/` 文档。它们属于用户工作，既不修改也不计入上述可复现基线。下列结论来自已提交 Python 源码和
当前 Java 分支；“完成”仅表示已有 Java 自动化证据，不能以文档或相似类名替代运行时证明。

## 已有 Java 闭环

1. 被动聊天、SQLite 会话、受限历史、OpenAI-compatible 文本/Tool/SSE、错误投影和单 JVM 会话串行。
2. 版本化渠道消息、CLI、Telegram 离线可靠投递、Loopback 控制面、受限 Plugin、Scheduler/Drift/Subagent
   和 sandbox Cutover 演练。
3. 只读 Markdown 与 Java 原生显式语义记忆、R10 Prompt Section/Core Persona、R11 Catalog/Approval
   Inbox/Pending Operation 无执行安全基础，以及默认关闭、当前 Session 限定的会话证据 Tool。自动语义检索使用
   Session 的 SHA-256 Binding，因此可覆盖 Telegram 等渠道；显式 Memory HTTP 管理 API 则有意只接受安全路径 ID，
   不能直接管理 `telegram:<chatId>` Scope。

这些闭环的证据在能力矩阵、Fixture Manifest 及默认、`failure`、`compat` Maven 门禁中维护；它们不等价于
已启用真实网络、真实工作区写入或生产迁移。

## 可验证的主要差距

| Python 已提交能力 | 证据位置 | Java 当前状态 | 对齐路径 |
| --- | --- | --- | --- |
| Skills 目录、frontmatter、工作区覆盖、依赖可用性、always 注入 | `agent/skills.py`、`agent/core/prompt_block.py`、`skills/*/SKILL.md` | R12-S1 Catalog/always 注入与 S4 deferred `read_skill` 已有 Kernel Port、受限只读 Adapter、严格 Properties 和 Tool Loop 回送；默认关闭 | Skill 执行、动态下载与 Python import 仍须单独 Contract |
| 文件、Shell、Web、消息、记忆、调度、Spawn、Peer、MCP 管理等 Tool | `agent/tools/*.py`、`agent/mcp/*`、`agent/peer_agent/*` | `current_time`、静态只读 MCP、R11-B3 默认关闭独立 Root 的 `read_file`/`list_dir`，以及 R11-B4 默认关闭当前 Session 的 `fetch_messages`/`search_messages` | B3 已有路径/链接、严格 UTF-8、预算、Deferred Schema 与纵向失败测试；B4 已有 opaque ID、显式 Turn Scope、SQLite 隔离、预算和三套门禁。其余仍按 Tool 逐一建立 Capability、Sandbox、Ledger、`UNKNOWN` 与 Smoke Contract |
| Provider 适配策略与 thinking/cache 细节 | `agent/provider.py`、`bootstrap/providers.py` | 一个 OpenAI-compatible Spring AI 适配器；Tool/流/超时/取消已有本地验收，P0 已增加安全拒绝/上下文超限的脱敏稳定分类 | [R10 Provider 协议计划](../plans/2026-07-19-r10-provider-protocol-alignment-plan.md)已完成 P0；P1 Options 已审计但等待运行语义选择，P2 reasoning、P3 裁剪恢复、P4 cache 观测仍未开始。真实 Provider Smoke 仍独立批准 |
| MCP Tool Client | `agent/mcp/client.py`、`bootstrap/toolsets/mcp.py` | 静态 stdio、只读 `tools/list`/`tools/call`；R12-S2 另有默认关闭的 `resources/list`/`prompts/list` 元数据目录与 Stale | Python 基线并不实现 Resources/Prompts；S2 是 Java-owned 安全扩展，不是对齐证据。远程认证、正文读取/注入、Streamable HTTP、取消与隔离须先证明必要性，再另立 Contract |
| Python Plugin 全生命周期、配置与 Tool Hook | `agent/plugins/*`、`agent/lifecycle/*` | Java ServiceLoader/stdio 观察型 Tap，含 API v2 Lifecycle Phase 映射 | R12-S3 已实现默认关闭的只读映射；可变 Hook/动态 Python import 需要独立授权 |
| QQ/Feishu/IPC、完整 Channel Host | `infra/channels/*`、`plugins/qqbot`、`plugins/feishu` | CLI/Telegram 离线纵向切片 | [R13 计划](../plans/2026-07-19-r13-dashboard-channel-alignment-plan.md)已冻结渠道逐一的身份、投递、恢复、真实 Smoke 与回退路径；当前不实现 IPC/QQ/Plugin Channel，真实 Telegram 继续冻结 |
| Dashboard 会话/消息/记忆管理与前端 | `bootstrap/dashboard_api.py`、`frontend/` | 后端 Loopback 状态/取消、审批 Inbox，零前端 | R13 计划已区分只读 API、受审批写入、前端供应链和渠道；先固定 API Fixture，且在解除 CLI+Web/前端冻结前不实现 |
| 完整 Proactive v2、外部源、反馈、自动记忆/Optimizer | `proactive_v2/*`、`memory2/*`、`core/memory/*` | 安全 NoOp/只读/显式记忆；R14-P0/P1 已冻结边界和无执行决策 | [R14 计划](../plans/2026-07-19-r14-proactive-peer-memory-automation-plan.md)已完成 28 Case 状态/Fake Source/Memory 禁止边界及 15 Case Gate + ReadOnly Drift 的无正文投影；P2–P5 的逐源、逐写入许可、审计、预算、恢复与回退仍未实现，不得自动启用，旧 Python `memory2` 数据仍不迁移 |
| Peer Agent 进程、Agent Card 与远端信任 | `agent/peer_agent/*` | 无 Peer Agent；R14-P0 仅有 `LOCAL_FAKE` 值对象 | R14 P4 仍须建身份/协议/信任/资源边界，并以本地 Fake 演练；真实进程、远程 A2A 和推送仍未授权 |
| 部署、真实数据迁移、灰度与 Python 退役 | `bootstrap/*`、`docker/`、`infra/` | 仅 sandbox Cutover 演练 | [R15 计划](../plans/2026-07-19-r15-production-migration-retirement-plan.md)已冻结配置、实例、数据副本、灰度与退役门槛；仅在书面授权、备份、双向回退及观察证据齐备后执行 |

## 优先级与依赖

1. **R11 B2c 仍未结束。** 恢复 Anchor、Reservation、Ledger 和 Message Contract 已有，但没有生产恢复器、
   认证 Resume/Cancel/Status 路由、获批 Capability 或真实执行。任何副作用 Tool 先经过独立 Contract。
2. **R12-S1/S4 Skills 已完成并验证。** S1 只读取受限 Java Skill Root 并提供 Catalog/always 注入；S4 仅在
   双重 `READ_ONLY` 与当前 Turn `tool_search` 后按名返回已审计正文。两者默认 `DISABLED`，不执行 Skill、不访问网络
   或写入 Workspace；Fixture 和完整三套 Maven 门禁已通过，仍不授权执行、动态下载或 Python import。
3. **R11-B3 只读文件浏览已完成并验证。** 它只允许独立显式 Root 的受预算文本读取和一层目录投影，默认关闭且不使用
   `${agent.workspace}`；它不解决 B2c 恢复，也不授权写入、Shell、网络或真实 Workspace。
4. **R11-B4 当前会话证据已完成并验证。** `fetch_messages`/`search_messages` 只在双重 `READ_ONLY` 后经当前 Turn
   `tool_search` 解锁，使用 opaque ID 和显式 Scope 查询当前 Session；它不对齐 Python 的原始 ID、跨会话或 FTS 表面。
5. R12-S3 API v2 只读生命周期 Tap 与 R12-S4 deferred Skill 正文读取均已完成并通过三套门禁；R12-S5 已实现默认关闭的
   当前 Scope `recall_memory` 受限替代，不读取 Python 记忆、不写入，也不提供跨 Scope/Keyword/RRF/时间线；R12-S2 的
   Assets 目录也已完成，但它是 Java-owned 扩展而非 Python MCP 对齐。R14-P0/P1 已完成离线边界与只读决策 Fixture，
   但没有接线运行时。实际主线仍是获得首个副作用 Capability 的逐工具 Contract 后完成 R11-B2c，随后推进 R13
   多渠道/控制面、R14 P2–P5
   和 R15 生产切换。
6. **渠道级显式记忆管理尚未对齐。** `ChatService` 的自动 Retrieval 可以用私有 SHA-256 Binding 查询
   `telegram:<chatId>` 的当前 Scope；但 Memory HTTP API 为避免危险路径和原始 Session 泄露，只接受
   `[A-Za-z0-9_-]+`。未来模型 Tool 必须从当前 Turn 接收私有 Binding，不能复用该管理 API；若需要渠道侧的显式
   管理，须先另立身份、授权和 API Contract。

“与 Akashic 一样完善”的完成条件是：本表每个条目都已由当前源码、版本化 Contract Fixture、对应失败路径
测试和阶段门禁证实，或有用户批准的替代方案；真实网络、密钥、用户数据、生产写入和 Python 退役还必须额外
获得操作授权，不能由本审计或自动测试推定。
