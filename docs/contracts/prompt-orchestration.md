# R10 版本化 Prompt 编排、Persona 与预算契约

- 阶段：R10
- 状态：已由持续 Java 重写目标授权冻结
- 日期：2026-07-18
- Python 基准：`agent/persona.py`、`prompts/agent.py`、`agent/context.py`、`agent/core/prompt_block.py`、`agent/prompting/assembler.py`、`agent/prompting/budget.py`
- 关联 ADR：[ADR-0015：使用项目拥有的版本化 Prompt Section 模型](../adr/0015-use-versioned-prompt-section-model.md)
- 关联设计：[R10 Prompt 编排设计](../specs/2026-07-18-prompt-orchestration-design.md)
- 实施计划：[R10 Prompt 编排计划](../plans/2026-07-18-r10-prompt-orchestration-implementation.md)

## 1. 目标与范围

R10 将 Java 从单一 `system.md` 迁移到可检查的 Prompt Section 编排。它对齐 Python 已有的身份、人格、行为规则、
技能目录、自我认知、长期记忆、会话上下文、近期语境、激活技能与检索记忆的固定顺序，并保持消息顺序为
`SYSTEM -> 已选历史 -> context frame -> 当前 USER`。

V1 迁移 Prompt 的表示、渲染、时间锚点、预算和安全裁剪；Skills 只作为受限的只读 Catalog/内容 Port，
不在本阶段执行 Skill、加载不受信代码、写入 Workspace 或扩大 Tool 权限。媒体、视觉、Tool Bundle、实际
Memory 写回、动态 Persona 和渠道实际投递分别由后续里程碑处理。

## 2. Section、顺序与消息位置

Section ID 必须严格小写，固定 priority 与位置如下：

| ID | priority | 位置 | 说明 |
| --- | ---: | --- | --- |
| `identity` | 10 | system | Akashic 身份、人格、工作区资产索引 |
| `behavior_rules` | 15 | system | 事实、时间、输出与当前已支持 Tool 的规则 |
| `skills_catalog` | 20 | system | 只读 Skill 摘要；无 Catalog 时省略 |
| `self_model` | 30 | system | `SELF.md` 的只读投影 |
| `long_term_memory` | 35 | system | `MEMORY.md` 的只读投影 |
| `session_context` | 40 | system | 环境、channel、chat/session 安全上下文 |
| `recent_context` | 45 | context frame | 去除 `## Recent Turns` 后的近期摘要 |
| `active_skills` | 50 | context frame | 明确请求且已验证的只读 Skill 内容 |
| `retrieved_memory` | 55 | context frame | 现有语义检索结果 |

同一 priority 或未知 ID 一律拒绝。空 Section 被省略，不生成空分隔符。System Section 之间用
`\n\n---\n\n` 分隔；context frame 使用现有 `<system-reminder data-system-context-frame="true">` 包装及
“不是用户陈述”的固定警告。任何 frame Section 都不得被提升为 System 消息，当前用户消息永远最后。

## 3. 模式、来源与时间

`agent.prompt.mode` 只接受严格大写 `MINIMAL|AKASHIC_CORE`，默认 `MINIMAL` 以保持当前部署行为。
`MINIMAL` 保留已有基础 Prompt 和 Memory Context；`AKASHIC_CORE` 额外启用项目资源中的身份、人格、行为、
时间和会话 Section。两种模式都受同一预算和消息位置约束。

Prompt 资源属于项目 classpath；工作区输入只能通过既有只读 Memory Profile 与后续明确定义的 Skill Catalog Port
进入。不得读取或输出 Token、原始媒体、任意文件内容、环境变量或绝对路径以外的未批准 Workspace 数据。记录
diagnostic 时只记录 Section ID、位置、字符/估算 token 数、裁剪计划和稳定码，不记录 Prompt 正文。

每轮以注入的 `Clock`、`ZoneId` 和可选 `PromptTurnContext` 生成一个 `request_time`。时间 envelope 至少包含
带 offset 的 request time、今天/昨天/明天/后天及星期；测试不得依赖墙钟。未知 channel/session 必须显式以稳定
占位值表示，不能猜测或复用其他会话值。

## 4. 预算与裁剪

预算以 Unicode code point 计数，估算 token 为 `max(1, ceil(codePoints / 3))`。`PromptBudget` 分别限制
system、context frame、总 Prompt token 与 Section 数；所有上限必须为有界正数。先渲染并计算，再按固定计划裁剪：
`full -> trim_skills_catalog -> trim_active_skills -> trim_long_term_memory -> trim_retrieved_memory`。

裁剪只能整体移除允许裁剪的 Section，绝不截断正文、历史消息或当前用户消息。基础身份、行为、会话、Self Model
和近期语境不可被裁剪；它们或剩余历史超过预算时，必须在模型调用前以
`PROMPT_BUDGET_EXHAUSTED` 拒绝。每次结果携带实际保留 Section、移除 Section、估算 token 和裁剪计划 ID。

## 5. 兼容、失败与验收

Java-owned `prompt/prompt-orchestration-v1.json` 固定大小写、顺序、空 Section、frame 边界、时间、模式、预算、
裁剪与拒绝码。`MINIMAL` 的既有 Context/Memory Golden 不得改变；`AKASHIC_CORE` 通过独立 Fixture 验收。

稳定码至少包括：`PROMPT_CONTRACT_INVALID`、`PROMPT_MODE_INVALID`、`PROMPT_BUDGET_EXHAUSTED`、
`PROMPT_SECTION_UNAVAILABLE`、`PROMPT_CONTEXT_INVALID`。默认、`failure`、`compat` 阶段门禁通过前不得进入
R11。真实 Workspace、真实 Provider、Telegram、技能执行、文件/Shell/Web 写入和生产切换不因 R10 获得授权。
