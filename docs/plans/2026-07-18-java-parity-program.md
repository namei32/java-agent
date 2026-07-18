# Java / Akashic Agent 全量对齐计划

- 状态：实施中；以 Python 基线提交 `b65a543` 与 Java `main` 当前源码为准
- 最近盘点：2026-07-18

## 盘点结论

R0–R9 已建立被动聊天、只读 Tool/MCP、SQLite、渠道可靠性、控制面、Plugin、受限 Proactive 与离线切换基础，
但还不能声称与 Akashic Agent 等价。Python 源码仍包含完整 Prompt/Persona/Skills、真实副作用工具、动态 Tool
搜索、多个渠道、Dashboard、远程 MCP、Peer Agent、真实主动来源、自动 Memory/Optimizer 及部署退役流程。

## 后续顺序

| 阶段 | 对齐主题 | Python 证据 | Java 完成标准 | 当前状态 |
| --- | --- | --- | --- | --- |
| R10 | Prompt、Persona、时间、预算 | `agent/context.py`、`agent/core/prompt_block.py`、`agent/persona.py`、`prompts/agent.py` | Section/Frame/预算/裁剪有 Fixture，`AKASHIC_CORE` 可离线验收 | 已实现并验证 |
| R11 | Tool Catalog、审批与逐工具 Capability | `agent/tool_bundles.py`、`agent/tools/*`、`agent/tool_runtime.py` | 可用人类审批、Durable Ledger、每个副作用 Tool 的幂等/UNKNOWN/沙箱 Contract | 实施中：B1 Tool Catalog 与 B2a 本地 Approval Inbox 已验证；B2b Pending Operation 的无执行状态机、AES-GCM 胶囊、v2 原子 Store 与 Fixture 已验证，条件提交、Ledger 与真实 Capability 未开始 |
| R12 | Skills、MCP 扩展与 Plugin 能力 | `agent/skills.py`、`agent/mcp/*`、`agent/plugins/*` | 受信 Skill Catalog/执行边界、MCP Resources/Prompts/Streamable HTTP、Plugin 生命周期兼容 | 未开始 |
| R13 | 多渠道、Dashboard 与控制面 | `infra/channels/*`、`bootstrap/dashboard_api.py` | 频道 Contract、真实渠道验收、前端/控制面完整 API 与安全边界 | 未开始 |
| R14 | Peer、真实 Proactive/Drift 与 Memory 自动化 | `agent/peer_agent/*`、`agent/core/proactive_*`、`core/memory/*` | 身份/信任、外部源、写入许可、审计、预算、恢复与回退契约 | 未开始 |
| R15 | 生产迁移与 Python 退役 | `bootstrap/*`、部署资产 | 真实副本验证、演练、灰度、观察、回退与逐项书面授权 | 未开始 |

每个阶段都必须先写 Contract/Spec/Plan，在隔离 worktree 按 RED/GREEN 实现，并通过默认、`failure`、`compat`
门禁。真实密钥、外部网络、用户 Workspace、真实渠道、生产副作用和 Python 停止不从本计划自动获得授权。
