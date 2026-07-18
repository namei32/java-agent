# R12-S4 按需只读 Skill 正文 Tool 契约

- 阶段：R12-S4
- 状态：已实现并验证；默认 `DISABLED`
- 契约版本：1
- 日期：2026-07-19
- Python 证据：`agent/skills.py` 的 `load_skill`、`load_skills_for_context` 与 `build_skills_summary`（基线 `b65a543`）
- 前置：[R12-S1 只读 Skill Catalog 契约](read-only-skill-catalog.md)
- 关联 ADR：[ADR-0024：以延迟只读 Tool 读取已审计的 Skill 正文](../adr/0024-expose-audited-skill-content-through-a-deferred-read-only-tool.md)

## 1. 目标与冻结边界

Python 在 Skill 摘要中暴露 `SKILL.md` 路径，再通过 `read_file` 按名称取得正文。Java 不向模型暴露物理路径，也不复用
通用 Workspace 文件读取；本切片以精确的 `read_skill` Tool 提供同等的“按名称取得正文”能力。

该 Tool 只读取已经通过 R12-S1 根、链接、严格 UTF-8、frontmatter、名称、文件大小与依赖可用性校验的 Skill 正文。输出
是剥离 YAML frontmatter 后的规范化正文，不含路径、Root、来源、环境变量、PATH、原始 metadata 或异常细节。它是
`READ_ONLY` Tool，不能执行 Skill、脚本、二进制、MCP、网络、Shell、Python import、目录枚举、文件写入或动态下载。

## 2. 模式、发现与 Schema

只有同时满足下列条件才注册 Tool：

1. `agent.skills.mode=READ_ONLY`；
2. `agent.tools.mode=READ_ONLY`；
3. 既有 Tool Catalog 通过 `tool_search` 在当前 Turn 解锁 `read_skill`。

`DISABLED` 时不创建根、枚举目录、检查依赖、读取正文或注册 Tool。Tool 固定为 deferred，不能因 Prompt Catalog、always
Skill 或历史 Turn 自动公开 Schema。它使用既有 `ToolCatalog` Session，解锁仅对当前 Turn 有效。

Schema 固定为：

```json
{
  "type": "object",
  "properties": {"name": {"type": "string"}},
  "required": ["name"],
  "additionalProperties": false
}
```

`name` 必须匹配 R12-S1 的 `^[a-z][a-z0-9-]{0,62}$`。它不是路径，不能包含 `/`、`\\`、`.` 片段、空格、Unicode
混淆或 NUL。Tool 只根据当前 R12-S1 合并视图匹配名称，并验证 Port 返回的名称相同；Workspace 覆盖 builtin 的确定规则
保持不变。

## 3. 正文、可用性与预算

只有 `available=true` 的已审计 Skill 可以读取。不存在、不可用、无效、链接/编码/文件预算失败、Root 不存在或读取时
候选失效均返回稳定、无路径的 `SKILL_CONTENT_UNAVAILABLE`。参数问题返回 `SKILL_CONTENT_INVALID_ARGUMENT`。正文超过
`agent.skills.max-read-code-points` 返回 `SKILL_CONTENT_BUDGET_EXCEEDED`，不得截断后继续返回指令。

`max-read-code-points` 默认 `20000`，范围 `1..65536`，并且不提高 R12-S1 的单文件字节上限。Bootstrap 必须拒绝
任何大于 `agent.tools.max-result-characters` 的配置，避免 Tool Runtime 在成功读取后才丢弃结果。成功结果是正文原文
（仅规范化 CRLF 并剥离 frontmatter）；Tool Runtime 的既有 Result/Wire/调用预算继续生效。

## 4. 隔离、不变量与验收

Tool 实现不得保存或接受物理路径；Adapter 每次读取都重新应用 S1 的真实 Root、无链接和 UTF-8 检查。无效候选不会使
剩余合法 Skill 不可用；任何 `RuntimeException` 都安全投影为上述稳定错误码，不能泄露本机细节。

Java-owned `skills/read-only-skill-content-v1.json` Fixture 固定 deferred 解锁、严格 Schema、正文/名称一致性、
不可用/不存在混淆、预算拒绝、稳定错误码和 v1 Tool Schema。Adapter/Bootstrap 测试另覆盖 Disabled 零 I/O、Workspace
覆盖、frontmatter 剥离、链接/UTF-8/候选变化拒绝及 Tool Loop 的 request/result 回送。测试只使用临时 Java Root 和
Fake Model；不读 Python Skill 目录、不启外部网络或进程。

实现证据（2026-07-19）：10 个 Fixture Case、Adapter/Properties/Catalog/Bootstrap/Tool Loop 测试均已通过；完整
`clean verify`、`-Pfailure verify`、`-Pcompat verify`、Spotless 与差异检查已在 R12 分支通过。

在本契约之外，Skill 执行、脚本、动态下载、Python import、原始 frontmatter/路径读取、任意 Workspace 文件读取和
可变 Prompt/Gate 仍须单独 Contract。R12-S4 不授予其中任何一项权限。
