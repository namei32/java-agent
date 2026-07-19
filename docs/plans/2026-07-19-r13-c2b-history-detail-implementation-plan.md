# R13-C2-B 受限历史详情实施计划

- 状态：C2-B0 决策门禁已完成；C2-B1 至 C2-B6 未授权、未开始
- 决策门禁：[R13-C2-B 受限历史详情决策门禁](../contracts/r13-c2-b-history-decision-gate.md)
- 当前实现基线：[R13-C2-A 内存终态历史目录 Contract](../contracts/r13-terminal-history-catalog.md)

## 1. 范围与不可变约束

本计划只在 B0-D1 至 B0-D6 全部获得明确值后才可执行。实施始终复用既有 `DISABLED`、Servlet、Loopback、Bearer、
Request ID 与 `Cache-Control: no-store` 边界；不得改动 C2-A 的语义。

每个任务只处理一个可观察边界，并依次完成 RED、最小 GREEN、diff/Spec 自审和一个本地提交。任何任务发现未分类列、
未知 role、无法证明 Scope、超预算、异常文本或数据源版本不匹配，都停止读取并返回稳定的脱敏结果。

## 2. 连续实施任务

### C2-B1：冻结版本化详情 Fixture

**输入：** 已确认的 B0-D1 至 B0-D6。

**先写 RED：** 新增 Java-owned Fixture 和 `@Tag("compat")` consumer，至少覆盖以下 30 类 Case：

| 分组 | 最少 Case | 固定内容 |
| --- | ---: | --- |
| 激活与请求形状 | 5 | DISABLED 零 Route、LOOPBACK/Servlet、GET-only、body、未知/重复 query |
| actor/Scope | 6 | 无 Bearer、过期/撤销、正确 Scope、错误 actor、跨 Scope、无法映射 |
| Ref 生命周期 | 5 | 新签发、actor/Scope binding、TTL、撤销、重复/竞争消费 |
| 投影与预算 | 6 | role 白名单、字段精确白名单、排序、空页、单条/单页/总字符上限 |
| 数据完整性 | 4 | 未知 schema、未知 role、损坏编码、未分类列 |
| 失败与关闭 | 4 | 存储异常、关闭、超时/取消、审计 sink 失败 |

**GREEN 目标：** Fixture 仅冻结 Contract，不创建 Controller、不打开数据库。Manifest 增加 SHA-256、Contract Evidence
和唯一 Case ID。

**完成条件：** Fixture 通过聚焦 compat 测试；Fixture 的 expected 值不包含正文、Session、actor、Route、token、
SQLite 路径或异常文本。

### C2-B2：安全值对象与只读 Port

**先写 RED：** 验证 `HistoryDetailRef`、`HistoryScopeCapability`、`HistoryPageCursor`、`HistoryDetailPage` 的格式、
TTL、actor/Scope binding、预算和 redacted `toString()`。

**最小实现：** 在 Kernel 定义不可由原始 Session/Route/Sender 构造的安全值与只读
`ControlHistorySnapshotPort`。Port 只接受内部 Scope Capability、服务端 Ref、固定分页参数和预算；不接受 SQL、
path、全文 query 或任意字段选择。

**完成条件：** 无 Spring/JDBC 依赖；所有安全值的 `toString()`、异常和 Result 均不泄漏内部标识或正文。

### C2-B3：隔离 Adapter 与本地 Fake 数据源

**先写 RED：** 使用临时 Java SQLite 或 Fake Repository 验证 Scope 先于读取、schema/role 白名单、稳定排序、
Budget 截断和异常脱敏。

**最小实现：** 只读 Adapter 使用显式固定 SQL、固定列和事务只读模式；读取后再次执行字段/字符预算。它只使用测试临时
数据库，不能打开用户、Python 或生产数据库。

**完成条件：** 零 DML、零网络；未知 schema/列/role、编码错误和存储失败均不产生宽松回退。

### C2-B4：详情服务与 Loopback Controller

**先写 RED：** 验证唯一已批准的 path、严格 query、有效 actor/Scope、固定 response 字段、`no-store` 和 Ref
失效的统一安全响应。

**最小实现：** Bootstrap 在 C2-B Contract 指定的唯一 `GET` Route 映射 service；仅从认证 principal 取得 actor，
由内部 mapping 取得 Scope Capability。Guard 与 controller 拒绝非 GET、body、未知/重复 query、原始 Session 或
historyRef 伪造。

**完成条件：** 默认 `DISABLED` 无 Controller；不增加 SSE、前端、CLI、写 API 或远程绑定。

### C2-B5：并发、取消、关闭与审计

**先写 RED：** 同一 cursor/Ref 的并发消费仅一个成功；关闭、取消、TTL、actor 撤销和存储异常不改变数据，也不暴露
是否存在其他 Scope 的历史。

**最小实现：** 使用锁或原子消费保障一次性值；以稳定码映射失败；如 B0-D6 获批，仅记录 hash-safe 事件且 sink
失败不改变 HTTP 结果。

**完成条件：** 竞争、关闭与异常测试均证明零 DML、零跨 Scope 读取、零正文/内部标识泄漏。

### C2-B6：阶段文档、门禁与交付决策

**执行：** 同步 Contract、Spec、ADR、Fixture、Manifest、Roadmap、能力矩阵和 Runbook；运行受影响模块的 default、
failure（如新增该类场景）、compat Fixture 与格式检查。

**R13 阶段门禁：** 不在 C2-B 小任务中例行运行完整三套门禁；C3 或 C5 达到阶段终点后，统一运行：

```bash
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

**完成条件：** 文档、Fixture、测试和 Git 状态一致；得到推送/PR 授权后才推送。

## 3. C2-B 后的 R13 顺序

1. **C3：受审批写 Capability。** 先选择一个具体 Capability；冻结参数 Capsule、Approval、幂等键、Ledger、
   UNKNOWN、取消、恢复、SQLite 事务和副作用审计，再从 RED/GREEN 实现。不得把 C2-B 的只读 Ref 用作写授权。
2. **C4：前端供应链。** 只有用户单独批准前端后，才先实现只读本机页面和 API 客户端；前端不持有永久 token，
不直连 SQLite，不新增远程监听或写按钮。
3. **C5：真实渠道纵向切片。** 先完成 Fake/离线 Adapter、取消/恢复/UNKNOWN 验证；获得网络、Secret、费用和
真实用户影响授权后，才执行真实 Telegram Smoke。
4. **合并与远程 CI。** 阶段三套门禁全绿、工作树干净且用户批准后，推送、创建/更新 PR、检查远程 CI；远程 CI
不等同于真实网络或生产授权。

## 4. 当前暂停点

当前只完成 C2-B0。C2-B1 的解除条件是用户逐项确认
[决策门禁](../contracts/r13-c2-b-history-decision-gate.md#3-必须由用户逐项确认的业务值)中的 B0-D1 至 B0-D6；在此之前，
下一项可执行工作是文档审查或 C2-A 的缺陷修复，而非 C2-B 生产实现。
