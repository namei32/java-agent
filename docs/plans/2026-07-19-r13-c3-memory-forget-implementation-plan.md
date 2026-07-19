# R13-C3 Scope 受限 Memory Forget 实施计划

- 状态：C3-C0（选择与 Contract）已完成；本计划只安排现有 R11-B2c 纵向链的回归核验。新的控制面写入口尚未获批准，不能开始实现。
- Capability：[`forget_memory` / `java-memory-forget-v1`](../contracts/r13-c3-approved-memory-forget-execution.md)
- 既有实现依据：[R11-B2c 实施计划](2026-07-19-r11-memory-forget-capability-implementation.md)
- 适用数据：仅临时 Java SQLite、固定时钟、Fake Approval、Fake Invoker

## 1. 目标

本计划的目标不是再造一个 Dashboard 写 API，而是确认首项受审批写 Capability 在 R13 的控制面边界下仍保持最小权限：

```text
non-empty forget_memory
  -> Pending + Approval + encrypted Capsule + Anchor
  -> local operator APPROVED
  -> one Reservation -> soft-forget once
  -> safe result, COMMITTED | UNKNOWN | COMMIT_UNREPORTED
```

唯一可写动作是：将**当前 Session Scope** 内、命中的 Java Native Memory 条目软失效为 `SUPERSEDED`。它不是物理删除，
不返回记忆正文，也不接受原始 Session、Scope、Approval、幂等键或路径。

## 2. 现有模块与责任

| 功能模块 | 现有实现 | 本计划要求 |
| --- | --- | --- |
| Tool 入口 | `MemoryForgetPendingToolset`、`MemoryForgetPendingProducer` | 非空调用只创建 Pending；不得批准或调用 Invoker |
| 审批与参数保护 | `MemoryForgetPendingService`、`PendingOperationStore`、加密 Capsule | Approval、Capsule、Operation 与 Anchor 必须精确绑定 |
| 恢复执行 | `MemoryForgetRecoveryCoordinator`、`MemoryForgetCapability` | 只在已批准、未过期、Anchor 匹配且 Reservation 独占时调用窄口 Port |
| Memory 写入 | `MemorySoftForgetPort`、`JdbcJavaMemoryStore` | 只在当前 Scope 软失效；幂等重放返回既有安全结果 |
| 本机控制面 | 既有 Pending Resume/Cancel/Status | 只接受 opaque operation ref；不增加创建、写入或自动恢复 Route |
| 配置装配 | `MemoryForgetCapabilityConfiguration` | 默认 `DISABLED`；显式条件不齐全即零 Bean/零 Tool/零 I/O |

## 3. 连续任务与完成条件

### C3-F1：冻结证据归属（已完成）

**输入：** 已批准的 C3 Contract 与既有 R11 Fixture。

**执行：** 不新增重复 Fixture；复用下列版本化资产并维护其各自的唯一测试责任：

| 资产 | 唯一责任 |
| --- | --- |
| `tools/memory-forget-capability-v1.json` | 参数规范化、Scope 隐藏、安全结果、幂等和结果不确定语义 |
| `tools/memory-forget-pending-producer-v1.json` | Deferred 可见性与“只创建 Pending、零 Invoker” |
| `tools/pending-operation-v1.json` | Capsule、Approval、Reservation、Ledger、Anchor 的通用持久化规则 |
| `control-plane/pending-recovery-control-v1.json` | Resume/Cancel/Status 的 Loopback 消息与零重放 |

**完成条件：** C3 文档只能引用这些已冻结 Fixture；不得以“C3”名义复制 R11 的并发或恢复矩阵。

### C3-F2：现有纵向链聚焦回归（下一项可执行工作）

**先运行以下测试，不先改代码：**

| 层次 | 测试 | 必须证明的结果 |
| --- | --- | --- |
| Kernel / Fixture | `MemoryForgetContractFixtureTest`、`GoldenManifestTest` | Fixture 仍可消费，且结果不泄漏内容或内部绑定 |
| Producer | `MemoryForgetPendingToolsetFixtureTest`、`MemoryForgetPendingServiceTest`、`MemoryForgetPendingToolLoopTest` | 搜索后才可见；非空调用只创建一个 Pending；失败与空输入零 Invoker |
| Recovery | `MemoryForgetRecoveryCoordinatorTest` | 批准 + Anchor + Reservation 才能执行一次；取消、过期、竞争、`UNKNOWN` 与 `COMMIT_UNREPORTED` 零重放 |
| SQLite | `MemoryForgetRecoveryIntegrationTest` | 临时数据库中的软失效、幂等 Ledger 与恢复状态一致 |
| Bootstrap | `MemoryForgetCapabilityConfigurationTest` | 默认零 Bean/零文件；非 Servlet 或缺少前置失败关闭；显式模式不创建 Worker |

**完成条件：** 测试全绿时不为“显示工作量”新增代码或测试。若出现 RED，只能在失败归属的模块写最小修复；任何修复仍不得新增 Route、真实数据库连接、网络或后台任务。

### C3-F3：结果记录与阶段判断

**通过时：** 在计划中记录命令、通过数量和工作树状态；C3 仍只完成“已选择 Capability 的离线执行链核验”。

**失败时：** 先判定失败属于 Fixture、Producer、Recovery、SQLite 或 Bootstrap；一次提交只修一个层次。并发测试只补缺失的独立不变量，不复制既有 Reservation/Anchor 矩阵。

**暂停条件：** 任何结果表明需要原始 Session/Memory ID、正文、生产库、网络、真实 operator 或新的 HTTP 写请求时，立即停止；这意味着进入 C3-M0，而不是扩大当前 Contract。

## 4. 新管理入口的硬性暂停点（C3-M0）

目前没有获批准的“控制面创建 Forget 请求”接口。C2-B 的 `detailRef`、`historyRef` 和 cursor 都是只读一次性引用，
不能转换为写权限；它们也不包含 Memory 目标。

因此，只有用户明确决定下列三项后，才能开始新的管理入口：

1. **目标引用：** 服务端如何签发、绑定、过期和撤销一个不泄漏 Memory ID 的写目标 Ref；
2. **请求表面：** 唯一 HTTP method/path、严格 body、重复请求与幂等行为；
3. **数据权限：** 允许的 Java 数据源、当前 Scope 映射方式，以及仍然禁止的字段和数据源。

未得到这三项决定前，不创建 Controller、Route、Request DTO、数据库查询或 Fixture。

## 5. 获得新入口授权后的精确 TDD 顺序

这部分不是当前授权的实现任务，只定义未来的执行顺序，防止跳过安全步骤：

1. **M1 RED Fixture：** 新增独立版本化 Fixture，至少覆盖默认关闭、Loopback/Servlet/Bearer、目标 Ref 签发与 TTL、
   actor/Scope 绑定、严格请求形状、重复/并发、Approval 创建、取消/过期、审计失败、`UNKNOWN` 与
   `COMMIT_UNREPORTED`；expected 不含 Memory ID、正文、Scope、Session、Capsule 或异常正文。
2. **M2 Kernel：** 定义不可伪造的目标 Ref 与请求/结果值对象；输入只能是经过认证的 operator、服务端 Ref 和固定动作，
   不接受 raw ID、Scope、Approval 或幂等键。
3. **M3 Application Producer：** 只把已验证目标转换为既有 `MemoryForgetPendingService` 的一次 Pending 创建；成功响应
   只投影安全状态和 opaque operation ref，不直接软失效。
4. **M4 Bootstrap / Controller：** 仅在显式 Loopback + Servlet 条件下映射唯一 Route，复用 Bearer、Origin、
   `no-store` 和审计边界；默认配置零 Route。
5. **M5 Failure Closure：** 用临时 Java SQLite/Fake 验证 Ref 单次消费、取消优先、并发单获胜者、关闭与审计失败；
   任何执行不确定都停在 `UNKNOWN`，不重放。
6. **M6 阶段门禁：** 更新 Contract/ADR/Fixture/Manifest/README/矩阵后，才统一运行 `clean verify`、
   `-Pfailure verify`、`-Pcompat verify`。

## 6. 非目标与退出条件

本计划不实现 Session/Message 删除、全局或物理 Memory 管理、`memorize`、Optimizer、消息投递、真实 Telegram、
前端、CLI+Web、远程访问或生产数据操作。

当前退出条件仅为：C3-C0 Contract 已冻结，C3-F2 聚焦回归通过且无新增权限。只有 M1–M6 在获得单独入口授权后全部完成，
才可以把 C3 标记为已实现；届时也仍不构成真实数据或部署启用授权。
