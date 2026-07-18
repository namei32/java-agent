# R11 O6 Pending Recovery Capability 实施计划

- 状态：已冻结，待开始
- 日期：2026-07-19
- Contract：[Pending Operation Session Anchor 与 Recovery Capability 契约](../contracts/pending-operation-recovery-capability.md)
- ADR：[ADR-0019：在恢复前冻结 Pending Operation 的 Session Anchor](../adr/0019-freeze-pending-operation-session-anchor-before-resume.md)

## 前置完成证据

- `cf5df99`：无执行 Pending Operation Ledger，Fixture 34 Case，聚焦 default/failure/compat 通过；
- `8c75d64`：Approval 消费、唯一 Reservation、并发单获胜者、Session `appendTurnIfNextSequence`；
- `3f90d19`、`fd794e1`：认证参数 Capsule、Schema v2 与原子创建。

## 连续 TDD 切片

1. **A1 Anchor Fixture（RED）。** 扩展 `pending-operation-v1`：合法 Anchor、未知版本、原始 Message/参数零泄漏、
   创建原子性、非零 Cursor 拒绝、新 Turn 失效、取消/到期、恢复 CAS、`UNKNOWN` 与 `COMMIT_UNREPORTED`。
2. **A2 Session Anchor Port/Model（GREEN）。** 新增不可变 Anchor 类型及 Fail Closed Port；默认实现拒绝，
   现有 Chat/Telegram/CLI 不调用。
3. **A3 SQLite Schema/迁移（GREEN）。** 对 `sessions.db` 使用版本化、前向检查的 Schema；完整初始 Turn、
   Pending 投影和 Anchor 同一事务写入；失败回滚全部。
4. **A4 条件恢复提交（GREEN）。** 只支持 Anchor 已知安全 Result 投影的 CAS 追加；旧 Cursor/取消/新 Turn
   返回非提交并产生稳定终态；不调用 Invoker。
5. **A5 Fake Capability 演练（RED → GREEN）。** 测试专用、显式构造的 Fake Invoker 连接已存在 Reservation/Ledger，
   验证 Running/Success/Unknown/Commit-unreported 和零重放；不注册 Spring Bean。
6. **A6 Loopback Contract（仅设计）。** 在 A1–A5 审查后才定义 Resume/Cancel/Status JSON；不实现真实路由。
7. **A7 阶段门禁。** A1–A6 完成后运行默认、`failure`、`compat` Reactor 全门禁与审查。

## 明确不做

不实现真实 Tool、真实 Telegram、远程 MCP、CLI+Web、前端、文件/Workspace 写入、Shell、网络、消息写入、
后台自动恢复、真实密钥配置或生产开关。任何单 Tool Capability 都需新的明确批准。
