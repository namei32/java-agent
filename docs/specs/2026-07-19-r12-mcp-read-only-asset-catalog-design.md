# R12-S2 MCP 只读 Assets Catalog 设计

- 状态：已实现并验证
- 日期：2026-07-19
- Contract：[MCP Resources / Prompts 只读目录契约](../contracts/mcp-read-only-asset-catalog.md)
- ADR：[ADR-0021](../adr/0021-keep-mcp-assets-as-catalog-only.md)

## 结构

```text
McpAssetProperties (DISABLED | CATALOG_ONLY)
        │
        ▼
existing static stdio McpRuntime
        │     └── initialize capability negotiation
        ▼
McpRuntime.assets() ── bounded resource/prompt accumulators
        │
        ▼
safe immutable descriptors only (no content, no prompt injection)
```

SDK、Reactor、JSON-RPC、Process 与 URI parsing 留在 `adapter-mcp`。Kernel 只接受项目拥有的 immutable descriptor
与空 Port；`McpRuntime.assets()` 是受限只读投影。Bootstrap 验证 Assets Mode 与既有 MCP Mode 的组合。Application 暂不消费该投影，避免远端元数据
成为模型 Context。

## 数据流

1. `DISABLED` 返回空 Catalog，不为 Assets 增加 SDK 声明或任何 list 调用。
2. `CATALOG_ONLY` 在现有 Server initialize 后，仅在 Server capabilities 表示可用时依次分页 `resources/list`、
   `prompts/list`。
3. Adapter 先验证 Cursor/页数/数量/UTF-8/字段预算，再稳定命名和排序；失败隔离到 Server Assets 状态。
4. Tool List Changed、Resource List Changed 或 Prompt List Changed 全部使对应 Connection Stale，直到运维重启。
5. Runtime 只向受限的观察/测试 Port 返回 descriptor；不暴露正文、路径、环境和参数值。

## TDD 分段

S1：Fixture、Kernel descriptor/Port、Disabled RED-GREEN；S2：SDK Gateway 和本地 Reference Server 分页发现；
S3：Adapter 限制、稳定名称、Stale 与失败隔离；S4：Bootstrap 严格 Properties/默认零调用；S5：architecture、
failure/compat 以及完整 Reactor 门禁。任何读取正文或 Chat 接线都不属于此设计。

## 实施结果

S1–S5 均已完成。`McpRuntime.assets()` 仅返回 `McpAssetCatalog` 的 immutable descriptor；`DISABLED` 或
普通 `STATIC_READ_ONLY` 均为空，`CATALOG_ONLY` 才在已声明的能力上发现 Assets。过限、超时、重复或不可信
Cursor 使该 Server 的 Assets 空化但保留 R5.1 Tool；收到 Assets List Changed 后目录立即 Stale 且不热刷新。
