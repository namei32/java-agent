# R12-S3 Plugin Lifecycle Tap 设计

- 状态：已实现并验证
- 日期：2026-07-19
- Contract：[Plugin 生命周期只读映射契约](../contracts/plugin-lifecycle-read-only-mapping.md)
- ADR：[ADR-0023](../adr/0023-add-read-only-plugin-lifecycle-tap-v2.md)

```text
TurnLifecycleEvent
        │
        ├── existing v1 TURN_TAP / TOOL_TAP (unchanged)
        │
        └── PluginLifecycleProjection (exact supported mapping)
                   │
                   ▼
          PluginTapEvent(LIFECYCLE_TAP, phase, safe hash, outcome, code)
                   │
                   ▼
       PluginTapDispatcher ── Java SPI / ExternalStdioPluginBridge.lifecycle.tap
```

Kernel 拥有 API version、Capability、Phase 与 Event 的不变量；Bootstrap 只将现有 `TurnLifecycleEvent` 投影为
安全事件。Application Dispatcher 继续在调用路径外执行、超时后隔离 Plugin。外部 Bridge 只为 API v2 Capability
发送 `phase` 参数，v1 的 `turn.tap`、`tool.tap` 和 `proactive.tap` JSON 不能变化。

实现已完成：L1 Fixture/Kernel RED-GREEN；L2 Java Lifecycle 映射及 v1 次数不变；L3 stdio Wire 与协议失败隔离；
L4 Properties/Disabled/ServiceLoader 启动边界；L5 完整 default/failure/compat 门禁及文档回写。
