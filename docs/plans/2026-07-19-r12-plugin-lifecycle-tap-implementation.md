# R12-S3 Plugin Lifecycle Tap 实施计划

- 状态：已实现并验证
- 分支：`agent/r12-skill-catalog`
- 前置：[R12-S3 Contract](../contracts/plugin-lifecycle-read-only-mapping.md)

1. **L1 Fixture/Kernel（完成）。** 固定 API v1/v2、`LIFECYCLE_TAP`、Phase、Hash/Outcome/稳定码和未知值的
   Java-owned Fixture；以 RED/GREEN 实现不可变模型与兼容构造器。
2. **L2 Lifecycle 投影（完成）。** 为现有 `TurnLifecycleEvent` 建立映射，验证 v2 精确一次、未映射零发布，且
   v1 `TURN_TAP`/`TOOL_TAP` 次数和 Hash 不变。
3. **L3 stdio Bridge（完成）。** 严格 Fake Transport 固定 `lifecycle.tap` params、v1 Wire 不变、错误/超时隔离和关闭。
4. **L4 Bootstrap（完成）。** 验证 API v2 Java Service/stdio 的显式选择、Disabled 零发现/线程/进程，以及 `READ_ONLY`
   全局边界；只新增既有 stdio 条目的 `api-version` 选择，不增加配置文件或外部目录发现。非法 Manifest 在进程工厂
   前拒绝，因而不会为无效 Capability 启动子进程。
5. **L5 Gate/Docs（完成）。** 已更新 Manifest、审计、矩阵、Roadmap 和 README；聚焦门禁及完整 `clean verify`、
   `-Pfailure verify`、`-Pcompat verify` 均已通过。

禁止：Gate、可变 Hook、Plugin Tool/Channel、Python import、Plugin 目录/Manifest 扫描、KV/Workspace、Shell、网络、
MCP、真实外部 Plugin、Secret 或生产启用。
