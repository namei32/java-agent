# ADR-0029：将 Skill 视为指令资产而非执行运行时

- 状态：已接受
- 日期：2026-07-19
- 决策者：Namei Agent Java 迁移维护者
- 关联：[ADR-0020：只读 Skill Catalog](0020-use-project-owned-read-only-skill-catalog.md)、[ADR-0024：按需读取 Skill 正文](0024-expose-audited-skill-content-through-a-deferred-read-only-tool.md)

## 背景

此前路线图把“Skill 执行”列为 R12 的未完成项，容易暗示 Python 存在一个将 `SKILL.md` 编译、解释或直接执行的
Runtime。对 Python 基线 `b65a543` 的重新审计表明并非如此：`agent/skills.py` 只发现、检查依赖并读取 Markdown；
`ActiveSkillsPromptBlock` 只把正文放入模型上下文；非 always Skill 则由模型通过通用 `read_file` 阅读路径取得正文。
`requires.bins`/`requires.env` 只影响可用性，不调用二进制或 Skill 内脚本。

Python 中“执行某个 Skill”的文字实际指模型遵循 Markdown 指令，然后调用已经注册的 `shell`、文件、MCP、消息、
调度或 Spawn Tool。Skill 本身不拥有执行权，也不绕过这些 Tool 的注册、风险、审批、幂等、`UNKNOWN` 或 Sandbox
边界。基线 Skill 的 `scripts/`、`references/`、URL 和命令同样只是文本提及，不是 Loader 自动执行的资源。

## 决策

1. Java 不实现通用 `SkillRunner`、脚本解释器、前置命令探测器、自动下载器或“依 Skill 授权”的 Tool 调用路径；这不是
   Python 基线的缺口。
2. R12-S1/S4 分别负责 **指令可得性**（受信 Catalog、always Prompt 注入）和 **按名正文读取**（deferred
   `read_skill`）。模型对正文的理解是模型行为，不形成新的 Java Runtime Surface。
3. Skill 正文要求的实际动作一律落回独立 Tool Capability：例如 Shell、写文件、网络、MCP 管理、消息推送、记忆写入、
   Scheduler 创建和 Spawn 都仍须各自的 R11+ Contract、风险分类、审批、预算、审计、恢复和 Smoke 授权。
4. `scripts/`、`references/`、附件和外链不因位于 Skill 目录而成为可读或可执行资源。需要它们时，必须由相应的
   只读/写入 Workspace、MCP、Shell、Web 或媒体 Contract 明确授权；R12-S4 的无路径 `read_skill` 边界保持不变。
5. 只有 Python 基线日后新增可复现、受测试覆盖的 Skill Runtime（而非 Markdown 提示语）时，才重新建立新的版本化
   Contract；它必须先证明能力授权模型，不能复用本 ADR 自动放行。

## 后果

- R12 的“Skill 执行未实现”不再作为实现缺口；正确剩余项是远程 MCP、可变 Plugin 生命周期及记忆写入等真实独立能力。
- `always` 注入和 `read_skill` 不会授予模型额外 Tool；当前 Java Tool Catalog 的 Deferred/Turn Scope 约束继续生效。
- Python 的 Drift 中“选择 Skill 并执行下一步”属于主动编排和具体 Tool 使用，归 R14/R11 的逐能力路径，不能据此引入
  Java SkillRunner。
- 本 ADR 仅更正能力边界和路线图，不改动生产代码、默认模式或既有 Fixture。现有 R12-S1/S4 测试继续证明 Markdown
  发现、无路径正文和默认零 I/O；每个未来 Tool Capability 仍需自己的 RED/GREEN 与阶段门禁。
