# R12-S4 按需只读 Skill 正文 Tool 设计

- 状态：已实现并验证
- 日期：2026-07-19
- Contract：[按需只读 Skill 正文 Tool 契约](../contracts/read-only-skill-content-tool.md)
- ADR：[ADR-0024](../adr/0024-expose-audited-skill-content-through-a-deferred-read-only-tool.md)

```text
ToolLoop → tool_search (current Turn) → read_skill(name)
                                      │
                                      ▼
                       SkillCatalogPort.readAvailable(name)
                                      │
                                      ▼
                  MarkdownSkillCatalogAdapter (S1 validation)
                                      │
                                      ▼
                 ToolResult(body | stable safe error code)
```

Kernel 扩展 `SkillCatalogPort` 的按名称、可用正文 Port，并保持 Catalog Snapshot 的公开字段不含正文。Workspace Adapter
从同一受限根重新构造合并视图，返回可用候选的 parsed body。Bootstrap 的 `ReadSkillTool` 只处理严格参数、名称一致性、
code-point 预算和稳定错误投影；它由既有 Tool Catalog 作为 deferred 内置 Tool 注册。

Tool Runtime、Provider Adapter 与 Prompt 不感知路径，也不新增 I/O 通道。`DISABLED` Port 返回空，且 Bootstrap 不注册
Tool。S1 的 always 注入保持原行为；S4 不能改变它的顺序、正文或预算。
