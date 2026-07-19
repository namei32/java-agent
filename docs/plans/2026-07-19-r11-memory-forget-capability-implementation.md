# R11-B2c Scope 受限 Memory Forget Capability 实施计划

- 状态：已获路径 A 批准；F1 尚未开始
- 日期：2026-07-19
- Contract：[获批的 Scope 受限 Memory Forget Capability](../contracts/approved-scope-bound-memory-forget.md)
- Design：[R11-B2c 设计](../specs/2026-07-19-r11-memory-forget-capability-design.md)

## 连续 TDD 切片

1. **F1 Fixture 与 Kernel RED→GREEN**：新增 Java-owned `memory-forget-capability-v1` Fixture，先写
   失败的 Kernel/Fixture Case，冻结输入去空白/去重、脱敏结果、Scope 隐藏、状态机与默认关闭。
2. **F2 Memory Schema V2 RED→GREEN**：先让 V1→V2、备份失败、未知 Schema、状态过滤和再激活 Case
   失败；实现 V2 migration/validator 与 SQLite SoftForget Store。只用临时数据库。
3. **F3 Capability 与 Pending 编排 RED→GREEN**：实现静态 Descriptor、Capsule 绑定、内部操作键、
   Approval/Anchor/Reservation/`RUNNING`/安全结果。不引入泛化任意 Tool 执行器。
4. **F4 Loopback Resume/Cancel/Status RED→GREEN**：仅实现现有 R11 消息 Contract 的认证本机映射；验证
   无 Body/Query、默认零路由、模式组合、脱敏、无 Worker 和 `UNKNOWN`/`COMMIT_UNREPORTED` 停机。
5. **F5 失败与并发 RED→GREEN**：覆盖取消、过期、新 Turn、绑定不符、单获胜者、事务回滚、关闭/中断和
   Conversation CAS 失败；不重复已有 B2b Fixture 的同一并发矩阵。
6. **F6 阶段门禁与审计**：更新 Golden manifest/SHA、矩阵、README、Runbook、Roadmap 与 Contract 状态，
   然后一次运行 `clean verify`、`-Pfailure verify`、`-Pcompat verify`。三者通过前不合并、不推送，也不做
   真实数据或网络 Smoke。

每个切片先提交可复现 RED 证据，再以最小生产实现转 GREEN；格式、聚焦单元/模块测试在每个切片结束运行。
完整三门禁只在 F6 执行，以避免重复耗时且不降低阶段验收。

## 完成定义

完成仅表示本地默认关闭 Capability 已经通过 Fixture 和三套离线门禁。它不表示可访问真实 Telegram、
网络、生产数据或旧 Python memory，也不授权后续 `memorize`、文件、Shell 或消息写入。
