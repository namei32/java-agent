# R11-B2c Scope 受限 Memory Forget Capability 实施计划

- 状态：已获路径 A 批准；F1–F6 与后续受控 Tool/Chat Pending 生产器均已完成，默认、`failure`、`compat` 三套完整阶段门禁已通过
- 日期：2026-07-19
- Contract：[获批的 Scope 受限 Memory Forget Capability](../contracts/approved-scope-bound-memory-forget.md)
- Design：[R11-B2c 设计](../specs/2026-07-19-r11-memory-forget-capability-design.md)
- 后续提案：[Tool/Chat Pending 生产器实施提案](2026-07-19-r11-memory-forget-tool-chat-pending-producer-plan.md)

## 连续 TDD 切片

1. **F1 Fixture 与 Kernel RED→GREEN（已完成）**：新增 Java-owned `memory-forget-capability-v1` Fixture，先写
   失败的 Kernel/Fixture Case，冻结输入去空白/去重、脱敏结果、Scope 隐藏、状态机与默认关闭。
2. **F2 Memory Schema V2 RED→GREEN（已完成）**：先让 V1→V2、备份失败、未知 Schema、状态过滤和再激活 Case
   失败；实现 V2 migration/validator 与 SQLite SoftForget Store。只用临时数据库。
3. **F3 Capability 与 Pending 编排 RED→GREEN（已完成核心）**：实现静态 Descriptor、Capsule 绑定、内部操作键、
   Approval/Anchor/Reservation/`RUNNING`/安全结果。不引入泛化任意 Tool 执行器。当前已完成静态恢复器和
   Pending 创建器；受控 Catalog/Chat 接线仍留在本切片后续工作。创建顺序固定为 Operation Store 原子创建、
   Session Pending CAS、CAS 失败固化 `STALE_SESSION`；不引入跨库事务或自动重试。
4. **F4 Loopback Resume/Cancel/Status RED→GREEN（已完成）**：认证本机映射只接受精确 Ref、无 Body/Query；
   成功只投影 `schemaVersion/state/updatedAt`；没有 Worker、Tool 或自动 Resume。
5. **F5 失败与并发 RED→GREEN（已完成）**：补齐严格 `DISABLED|LOOPBACK_APPROVAL` 配置、AES-256 Capsule
   Key、Servlet 限制、三项模式前置与默认零 Bean/路由验证；并覆盖取消、过期、新 Turn、绑定不符、单获胜者、
   事务回滚、Anchor 故障与 Conversation CAS 失败。真实临时 SQLite 的 Resume/Cancel 竞争只允许一个终态路径，
   不重复已有 B2b Fixture 的同一并发矩阵。
6. **F6 阶段门禁与审计（已完成）**：验证 Golden manifest 的 43 个条目及 Pending Recovery Fixture SHA，补齐逐 Case
   的生产/聚焦测试归属，并更新矩阵、README、Runbook、Roadmap 与 Contract 状态；`clean verify`、`-Pfailure verify`
   与 `-Pcompat verify` 已全部通过。没有合并、推送、真实数据或网络 Smoke。

每个切片先提交可复现 RED 证据，再以最小生产实现转 GREEN；格式、聚焦单元/模块测试在每个切片结束运行。
完整三门禁只在 F6 执行，以避免重复耗时且不降低阶段验收。

## 完成定义

完成仅表示本地默认关闭 Capability 已经通过 Fixture 和三套离线门禁。它不表示可访问真实 Telegram、
网络、生产数据或旧 Python memory，也不授权后续 `memorize`、文件、Shell 或消息写入。

模型可见性和 Pending 创建已由独立的[Tool/Chat Pending 生产器实施计划](2026-07-19-r11-memory-forget-tool-chat-pending-producer-plan.md)实现并通过自身 P6 审计；它仍不授权执行、网络或真实数据。
