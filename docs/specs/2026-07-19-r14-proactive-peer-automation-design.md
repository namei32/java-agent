# R14 P0 主动、记忆与 Peer 自动化边界设计

- 状态：已实现；仅 Kernel Contract/Fixture
- 日期：2026-07-19
- Contract：[R14 P0 边界契约](../contracts/r14-proactive-peer-automation-boundaries.md)
- ADR：[ADR-0027](../adr/0027-freeze-r14-proactive-peer-automation-boundaries.md)

```text
Versioned Fixture (28 cases)
             |
             v
Kernel contract values
  - ProactiveJobTransition
  - ProactiveSourceItem(FIXED_LOCAL)
  - ProactiveDeliveryBoundary(PENDING_APPROVAL | NOT_REQUESTED)
  - ProactiveMemoryMutation(NONE)
  - PeerIdentity(LOCAL_FAKE) / PeerTaskRef / PeerTaskState
             |
             +-- no Bootstrap wiring
             +-- no scheduler start
             +-- no DB / Provider / network / process / delivery / Memory DML
```

P0 放在 `agent-kernel`，使未来 P1–P4 可以用同一状态和不透明引用做 Fixture 驱动设计，而无需让 Bootstrap 取得过早的
执行能力。R8 既有 `ProactiveRuntime` 和 `JdbcProactiveJobStore` 不依赖这些新类型，避免在尚无 Approval/Capability/
Recovery 语义时形成隐式调用链。

测试先增加 Fixture consumer，确认缺少类型时构建失败（RED）；随后实现纯值校验并运行 `compat` 的定向测试（GREEN）。
P1 已在上述边界上实现，但仍未接线：它将 FIXED_LOCAL 与已有只读 Drift 组合为有界
SKIPPED/PENDING_APPROVAL/CANCELLED 投影，仍不可交给模型、自动写入、自动投递或创建真实 Peer。P2 起每个外部
Source 和 Delivery 都需要独立 Capability Contract。
