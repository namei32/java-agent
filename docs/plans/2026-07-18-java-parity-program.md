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

## 如何阅读本计划

- 想知道“系统现在能做什么、下一步补哪个功能”，先看下方**功能模块总览**。
- 想知道某项工作如何落地，进入对应的 Contract，再阅读该功能的实施计划；实施计划必须给出对象、测试、完成条件和暂停点。
- R 编号只表示交付顺序，不是用户功能菜单。下方的“阶段索引”保留它，便于追溯历史提交和 Fixture。

## 功能模块总览

| 功能模块 | 已具备的能力 | 当前缺口 | 当前下一步 |
| --- | --- | --- | --- |
| 对话与 Provider | 被动聊天、消息协议、流式投影、Prompt/Persona、受限 Provider 失败恢复 | 跨 Turn reasoning、真实 Provider 运行与完整 Python Chat Lane | 保持默认关闭；任何真实 Provider/数据保留扩展另立 Contract |
| 记忆与上下文 | Java Native Memory、当前 Scope 检索、Context 注入、只读 `recall_memory`、已验证的 `forget_memory` 离线链 | 自动写回、Optimizer、全局管理与真实 Embedding | C3-M0 保持无直接管理入口；其他 Memory 写入逐项选择 |
| Tool 与审批安全 | Catalog、Deferred Tool、Approval Inbox、Capsule、Reservation、Ledger、Anchor、首个 `forget_memory` 链 | 其他写 Tool、真实审批操作与 Sandbox | C3-F2/F3/M0 已完成；不自动扩展为新写 API，未来入口须建立新的目标 Ref/请求/数据权限 Contract |
| 扩展与外部资产 | 只读 MCP、Skill Catalog/Content、Plugin 生命周期 Tap | 远程 MCP、可变 Plugin、脚本/网络执行 | 维持只读和默认关闭；逐能力评审外部访问 |
| 控制面与渠道 | Loopback 状态/取消/SSE、控制索引、零正文历史、Telegram 离线链 | 管理写入、前端、多渠道与真实渠道 Smoke | C3 已选择首项写 Capability；前端、真实 Telegram 和远程访问继续冻结 |
| 主动运行与交付 | 受限 Scheduler/Drift/Subagent、只读主动决策、P2 Fake Delivery、P3 Fake Memory、P4 Fake Peer Pending/Recovery、P5 静态 Deferred Catalog | 外部源、真实 Memory DML/Optimizer、真实 Peer、真实生产迁移 | P2–P4 都是未接线的独立 Approval/Anchor/AES-GCM Capsule 与单次 Fake Port；P5 只是零参数 Placeholder Schema。没有 SQLite Adapter、网络、真实投递/进程或自动执行；先冻结每一类外部输入/副作用 Contract，再考虑接线 |

## 阶段索引（按实施顺序）

| 阶段 | 对齐主题 | Python 证据 | Java 完成标准 | 当前状态 |
| --- | --- | --- | --- | --- |
| R10 | Prompt、Persona、时间、预算与 Provider 协议 | `agent/context.py`、`agent/core/prompt_block.py`、`agent/persona.py`、`prompts/agent.py`、`agent/provider.py` | Section/Frame/预算/裁剪有 Fixture，`AKASHIC_CORE` 可离线验收；Provider 的失败/Options/thinking/cache 分层对齐 | Prompt/Persona、Provider P0 脱敏失败分类、P1 默认关闭的受信 Options、P2 仅 `DEEPSEEK` + `SAFE_LOCAL` 的有界单轮 reasoning 回放、P3 默认关闭的 Tool 前非流式上下文恢复，以及 P4 提交后匿名 cache prompt/hit 聚合均已实现并验证；P2b 的跨 Turn reasoning 历史与空占位符仍待数据保留/请求扩展 Contract |
| R11 | Tool Catalog、审批与逐工具 Capability | `agent/tool_bundles.py`、`agent/tools/*`、`agent/tool_runtime.py` | 可用人类审批、Durable Ledger、每个副作用 Tool 的幂等/UNKNOWN/沙箱 Contract | 实施中：B1–B4 及默认关闭的 B2c 本地恢复切片已实现；B2c 包含 Scope 批量软失效、Capsule、Reservation、显式 Resume/Cancel/Status、24 Case Fixture、严格 Loopback 装配和 13 Case 受控 Pending Producer。该 Producer 只在搜索后创建 Pending，不启动 Worker、自动 Resume 或真实数据执行；其他副作用 Tool 仍无实现 |
| R12 | Skills、MCP 扩展、Plugin 与受限 Memory Tool | `agent/skills.py`、`agent/mcp/*`、`agent/plugins/*`、`agent/tools/recall_memory.py` | 受信 Skill 指令边界、Python MCP Tool Client、Plugin 生命周期兼容、受限当前 Scope 召回 | S1、S3、S4 已通过三套门禁；S2 是 Java-owned Assets 目录。S5 已实现默认关闭的 `recall_memory` 安全替代，不声称 Python 检索等价；ADR-0029 确认 Python 没有待迁移的 Skill Runner，Skill 指令中的动作改由 R11 逐 Tool Capability 处理；远程 MCP、可变 Plugin 与任何记忆写入仍未开始 |
| R13 | 多渠道、Dashboard 与控制面 | `infra/channels/*`、`bootstrap/dashboard_api.py` | 频道 Contract、真实渠道验收、前端/控制面完整 API 与安全边界 | R13-C0/C1、C2-A 与 C2-B 已完成：20 Case C1 Contract 已落为默认关闭的本机 `GET /api/v1/control/index`；22 Case C2-A Contract 已落为内存 `GET /api/v1/control/history`；31 Case C2-B Contract 已落为零正文 `GET /api/v1/control/history/detail`。C2-B 默认 Scope/Port 拒绝且只在临时 Java SQLite/Fake 验证，无持久化数据自动读取。C3-C0 至 F3/M0 已选择并验证既有 Scope 受限 `forget_memory` 的受审批执行边界（54 个聚焦测试通过）；M0 明确不新增 Route、Memory 目标 Ref、Worker 或真实数据执行。C4–C5 未开始；现有控制面不是 Python Dashboard、IPC/QQ/Plugin Channel 或前端对齐，远程、真实 Telegram、CLI+Web 与前端继续冻结 |
| R14 | Peer、真实 Proactive/Drift 与 Memory 自动化 | `agent/peer_agent/*`、`agent/core/proactive_*`、`core/memory/*` | 身份/信任、外部源、写入许可、审计、预算、恢复与回退契约 | P0–P5 已完成本地 Fake/静态 Contract：P3 为固定 `NOTE` 捕获，P4 为唯一静态 Peer，P5 为两个 Deferred 空参数 Schema；P3/P4 均有 Approval/Anchor/AES-GCM Capsule/Recovery 和 `UNKNOWN`/零重放验证。仍无 Bootstrap/SQLite Adapter/网络/真实渠道、真实进程或自动执行；不构成 Python 自动记忆、外部主动源、Peer A2A 或真实投递对齐 |
| R15 | 生产迁移与 Python 退役 | `bootstrap/*`、部署资产 | 真实副本验证、演练、灰度、观察、回退与逐项书面授权 | 差距审计与[分阶段计划](2026-07-19-r15-production-migration-retirement-plan.md)已冻结；真实迁移未开始。R9 仅证明 sandbox 演练，不能替代 Java 部署资产、数据副本、灰度、观察或 Python 退役授权 |

每个阶段都必须先写 Contract/Spec/Plan，在隔离 worktree 按 RED/GREEN 实现，并通过默认、`failure`、`compat`
门禁。真实密钥、外部网络、用户 Workspace、真实渠道、生产副作用和 Python 停止不从本计划自动获得授权。
