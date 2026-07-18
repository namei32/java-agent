# 2026-07-19 Akashic / Java 完成度审计

- 状态：当前事实；后续实现必须以本审计和各阶段 Contract 为准
- Python 证据基线：`akashic-agent` 提交 `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Java 证据基线：`agent/r11-tool-capability` 提交 `7460021`
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
   Inbox/Pending Operation 无执行安全基础。

这些闭环的证据在能力矩阵、Fixture Manifest 及默认、`failure`、`compat` Maven 门禁中维护；它们不等价于
已启用真实网络、真实工作区写入或生产迁移。

## 可验证的主要差距

| Python 已提交能力 | 证据位置 | Java 当前状态 | 对齐路径 |
| --- | --- | --- | --- |
| Skills 目录、frontmatter、工作区覆盖、依赖可用性、always 注入 | `agent/skills.py`、`agent/core/prompt_block.py`、`skills/*/SKILL.md` | R12-S1 已有 Kernel Catalog/Port、受限只读 Adapter、严格 Properties 和 `AKASHIC_CORE` Prompt 注入；默认关闭 | 按需正文/执行、动态下载与 Python import 仍须单独 Contract |
| 文件、Shell、Web、消息、记忆、调度、Spawn、Peer、MCP 管理等 Tool | `agent/tools/*.py`、`agent/mcp/*`、`agent/peer_agent/*` | 仅 `current_time`、静态只读 MCP；R11 无执行恢复基础 | R11 按 Tool 逐一建立 Capability、Sandbox、Ledger、`UNKNOWN` 与 Smoke Contract；禁止批量放开 |
| Provider 适配策略与 thinking/cache 细节 | `agent/provider.py`、`bootstrap/providers.py` | 一个 OpenAI-compatible Spring AI 适配器 | 逐 Provider 建立 Options/Message/Streaming Fixture；真实 Provider Smoke 仍独立批准 |
| Resources/Prompts/Streamable HTTP MCP 与动态管理 | `agent/mcp/*.py`、`bootstrap/toolsets/mcp.py` | 静态 stdio、只读 `tools/list`/`tools/call`；R12-S2 已实现默认关闭的 `resources/list`/`prompts/list` 元数据目录与 Stale | 远程认证、正文读取/注入、Streamable HTTP、取消与隔离仍需后续 Contract |
| Python Plugin 全生命周期、配置与 Tool Hook | `agent/plugins/*`、`agent/lifecycle/*` | Java ServiceLoader/stdio 观察型 Tap | R12-S3 补只读 lifecycle 映射；可变 Hook/动态 Python import 需要独立授权 |
| QQ/Feishu/IPC、完整 Channel Host | `infra/channels/*`、`plugins/qqbot`、`plugins/feishu` | CLI/Telegram 纵向切片 | R13 按渠道分别冻结身份、投递、恢复、真实 Smoke 与回退 |
| Dashboard 会话/消息/记忆管理与前端 | `bootstrap/dashboard_api.py`、`frontend/` | 后端 Loopback 状态/取消，零前端 | R13 先固定 API Fixture，再实现安全前端/历史操作 |
| 完整 Proactive v2、外部源、反馈、自动记忆/Optimizer | `proactive_v2/*`、`memory2/*`、`core/memory/*` | 安全 NoOp/只读/显式记忆 | R14 逐源、逐写入许可、审计、预算、恢复与回退；不得自动启用 |
| Peer Agent 进程、Agent Card 与远端信任 | `agent/peer_agent/*` | 无 Peer Agent | R14 先建身份/协议/信任/资源边界，后建本地 Fake 演练 |
| 部署、真实数据迁移、灰度与 Python 退役 | `bootstrap/*`、`docker/`、`infra/` | 仅 sandbox Cutover 演练 | R15 仅在书面授权、备份、双向回退及观察证据齐备后执行 |

## 优先级与依赖

1. **R11 B2c 仍未结束。** 恢复 Anchor、Reservation、Ledger 和 Message Contract 已有，但没有生产恢复器、
   认证 Resume/Cancel/Status 路由、获批 Capability 或真实执行。任何副作用 Tool 先经过独立 Contract。
2. **R12-S1 Skills 已完成并验证。** 它只读取受限 Java Skill Root、不会执行 Skill 文本、不会注册 Tool、不会
   访问网络或写入 Workspace；默认 `DISABLED`。13 Case Fixture 及完整三套 Maven 门禁已通过；它不绕过 R11，
   更不授权按需正文或执行。
3. R12-S2 的目录发现已完成；R12-S3 生命周期、R13 多渠道/控制面、R14 主动/Peer/自动记忆、R15 生产切换严格按顺序。

“与 Akashic 一样完善”的完成条件是：本表每个条目都已由当前源码、版本化 Contract Fixture、对应失败路径
测试和阶段门禁证实，或有用户批准的替代方案；真实网络、密钥、用户数据、生产写入和 Python 退役还必须额外
获得操作授权，不能由本审计或自动测试推定。
