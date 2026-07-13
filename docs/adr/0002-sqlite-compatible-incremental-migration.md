# ADR-0002：采用 SQLite 兼容优先的渐进迁移

- 状态：已接受
- 日期：2026-07-12

## 背景

现有 Python 工作区包含 SQLite 会话数据和 Markdown 记忆。直接迁移 Schema 或同时运行两个写入者可能造成不可逆的数据损坏。

## 决策

Java 使用 SQLite、显式 SQL 和显式事务，并兼容 Python 已有的 `sessions` 与 `messages` 核心字段。初始化只能补建不存在的已知表；发现同名但不兼容的表时必须启动失败，不得静默改写。

迁移期间遵循：

1. Python 是尚未迁移行为的基准。
2. Python 与 Java 不得同时写同一工作区。
3. 首次 Java 写入真实工作区前必须备份数据库及 WAL/SHM 文件。
4. 未知 JSON、TOML 和 Markdown 内容尽量原样保留。
5. 每个新增持久化能力必须提供兼容测试、回滚测试和可逆切换方案。

## 结果

可以分阶段切换并复用现有数据，但 Schema 演进更保守，需要维护 Python 兼容夹具和 Golden Test。
