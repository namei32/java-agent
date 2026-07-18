# R11 B2b Pending Operation 实施计划

- 状态：契约已冻结；尚未开始代码实施
- 日期：2026-07-18
- 前置：B2a `7200978`、绑定标识修复 `d76466c`

## 切片

1. **O1 Contract Fixture（RED）。** 新建 Java-owned `tools/pending-operation-v1.json`：无 Key/默认关闭、加密 AAD、密文篡改、CAS、取消/过期、新 Turn、重启、消费+Reservation 原子性、`UNKNOWN`、条件 Conversation 提交与零 Invoker。
2. **O2 Application State Machine（GREEN）。** 新增不可变 `PendingOperation`、随机 `operationRef`、状态转换与 Capsule Port；不接 `ChatService`、`ApprovalPort` 或任何 Invoker。
3. **O3 SQLite Schema v2（GREEN）。** 明确 v1→v2 迁移、AES-GCM Adapter、Approval/Operation/Reservation 单事务 CAS、事务失败 Fail Closed；先对已有 B2a 历史迁移做只读兼容验证。
4. **O4 Session 条件提交（GREEN）。** 扩展 `SessionRepository` 为 `appendTurnIfNextSequence` 并以 SQLite 原子条件更新验证新 Turn 失效；不执行 Tool。
5. **O5 Capability 接缝（仅 Contract）。** 设计版本化 Pending 投影、显式 Resume/Cancel API、密钥轮换和单 Tool Capability 的连接点；没有独立批准不得注册 Bean。
6. **O6 阶段验收。** B2b 全部实现后才与 R11 剩余 Capability 统一运行默认、`failure`、`compat` Reactor 门禁。

## 不做

不迁移 Python 的宽松 Hook、副作用工具、真实 Telegram、远程访问、CLI+Web、前端、后台自动恢复或任何真实网络/文件/Shell/消息写入。
