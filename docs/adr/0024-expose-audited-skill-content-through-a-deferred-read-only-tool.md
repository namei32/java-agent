# ADR-0024：以延迟只读 Tool 读取已审计的 Skill 正文

- 状态：已接受
- 日期：2026-07-19
- 决策范围：R12-S4

## 背景

Python 的 Skill 摘要会指导模型按路径读取 `SKILL.md`。Java S1 有无路径 Catalog 与 always 注入，但非 always Skill
没有按名称获取正文的路径；让模型直接调用通用 `read_file` 会重新暴露任意 Workspace 路径和 Root 边界。

## 决策

新增仅在 Skill/Tool 都显式 `READ_ONLY` 时注册的 deferred `read_skill` Tool。它接收严格 Skill 名，复用 S1 合并与
验证结果，只返回可用 Skill 的无 frontmatter 正文；调用前必须通过当前 Turn 的 `tool_search` 解锁。

## 后果

模型获得按名称的 Skill 正文能力，而不获知或控制本机路径。正文仍可能是用户维护的指令，因此 Result 预算、Tool Loop
顺序、Tool Call 审计和模型侧不可信内容规则保持适用。此决策不运行 Skill，也不为 Plugin、MCP、Shell、网络、写入、
动态发现或可变 Hook 提供授权。
