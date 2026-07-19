# Java / Akashic Agent 全量对齐计划

- 状态：实施中；以 Python 基线提交 `b65a543` 与 Java 当前实现为准
- 最近盘点：2026-07-19；详见 [完成度审计](../architecture/2026-07-19-akashic-java-completion-audit.md)

## 盘点结论

R0–R9 已建立被动聊天、只读 Tool/MCP、SQLite、渠道可靠性、控制面、Plugin、受限 Proactive 与离线切换基础，
但还不能声称与 Akashic Agent 等价。Python 源码仍包含完整 Prompt/Persona/Skills、真实副作用工具、动态 Tool
搜索、多个渠道、Dashboard、远程 MCP、Peer Agent、真实主动来源、自动 Memory/Optimizer 及部署退役流程。

Tool 层不能只按“是否存在相似 Runtime”盘点：Python 注册的每一个 Tool 与 Java 当前 Schema、Scope、结果和风险边界
的逐项差异见[Tool Capability 清单](../architecture/2026-07-19-python-tool-capability-parity-inventory.md)。该清单是 R11–R14
的执行顺序补充，不授予任何副作用或网络权限。

## 后续顺序

| 阶段 | 对齐主题 | Python 证据 | Java 完成标准 | 当前状态 |
| --- | --- | --- | --- | --- |
| R10 | Prompt、Persona、时间、预算与 Provider 协议 | `agent/context.py`、`agent/core/prompt_block.py`、`agent/persona.py`、`prompts/agent.py`、`agent/provider.py` | Section/Frame/预算/裁剪有 Fixture，`AKASHIC_CORE` 可离线验收；Provider 的失败/Options/thinking/cache 分层对齐 | Prompt/Persona、Provider P0 脱敏失败分类、P3 默认关闭的 Tool 前非流式上下文恢复，以及 P4 提交后匿名 cache prompt/hit 聚合均已实现并验证；P1 Options 已审计、等待运行语义选择，P2 未开始 |
| R11 | Tool Catalog、审批与逐工具 Capability | `agent/tool_bundles.py`、`agent/tools/*`、`agent/tool_runtime.py` | 可用人类审批、Durable Ledger、每个副作用 Tool 的幂等/UNKNOWN/沙箱 Contract | 实施中：B1 Catalog、B2a Inbox 与 B2b 的状态机、AES-GCM、v2 原子 Store、一次性 `CONSUMED`/`RESERVED`、Ledger 终态、Session 条件提交、初始 Anchor 原子写入、安全 Result 条件提交、测试专用 Fake Capability 零重放演练和 Resume/Cancel/Status Message Contract 已验证；B3 已实现独立 Root 的 `read_file`/`list_dir`（严格 UTF-8、预算、链接拒绝、Deferred Schema、默认关闭）。B4 当前 Session 只读 `fetch_messages`/`search_messages` 已完成并通过三套门禁：opaque ID、显式 Turn Scope、当前 Session SQLite 约束、deferred 解锁、预览/总量预算与默认关闭。生产恢复编排、路由、Capability 或真实副作用执行仍未完成 |
| R12 | Skills、MCP 扩展、Plugin 与受限 Memory Tool | `agent/skills.py`、`agent/mcp/*`、`agent/plugins/*`、`agent/tools/recall_memory.py` | 受信 Skill 指令边界、Python MCP Tool Client、Plugin 生命周期兼容、受限当前 Scope 召回 | S1、S3、S4 已通过三套门禁；S2 是 Java-owned Assets 目录。S5 已实现默认关闭的 `recall_memory` 安全替代，不声称 Python 检索等价；ADR-0029 确认 Python 没有待迁移的 Skill Runner，Skill 指令中的动作改由 R11 逐 Tool Capability 处理；远程 MCP、可变 Plugin 与任何记忆写入仍未开始 |
| R13 | 多渠道、Dashboard 与控制面 | `infra/channels/*`、`bootstrap/dashboard_api.py` | 频道 Contract、真实渠道验收、前端/控制面完整 API 与安全边界 | 差距审计与[分阶段计划](2026-07-19-r13-dashboard-channel-alignment-plan.md)已冻结；实现未开始。现有 Loopback 控制面、审批 Inbox、CLI/Telegram 离线切片不是 Python Dashboard、IPC/QQ/Plugin Channel 或前端对齐；远程、真实 Telegram、CLI+Web 与前端继续冻结 |
| R14 | Peer、真实 Proactive/Drift 与 Memory 自动化 | `agent/peer_agent/*`、`agent/core/proactive_*`、`core/memory/*` | 身份/信任、外部源、写入许可、审计、预算、恢复与回退契约 | P0 已完成 28 Case 状态/Fake Source/Memory 禁止/Fake Peer 边界；P1 已完成 15 Case Gate + Fake Source + ReadOnly Drift 的无正文 SKIPPED/PENDING_APPROVAL/CANCELLED 投影和 failure 测试，且无 Bootstrap 接线。P2–P5 仍未实现；R8/P0/P1 不是 Python 自动记忆、外部主动源、Peer 进程/A2A 或真实投递对齐 |
| R15 | 生产迁移与 Python 退役 | `bootstrap/*`、部署资产 | 真实副本验证、演练、灰度、观察、回退与逐项书面授权 | 差距审计与[分阶段计划](2026-07-19-r15-production-migration-retirement-plan.md)已冻结；真实迁移未开始。R9 仅证明 sandbox 演练，不能替代 Java 部署资产、数据副本、灰度、观察或 Python 退役授权 |

每个阶段都必须先写 Contract/Spec/Plan，在隔离 worktree 按 RED/GREEN 实现，并通过默认、`failure`、`compat`
门禁。真实密钥、外部网络、用户 Workspace、真实渠道、生产副作用和 Python 停止不从本计划自动获得授权。
