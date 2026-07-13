# Python/Java Golden Test 基线设计

- 状态：已实现并验证
- 日期：2026-07-13
- 关联 Roadmap：R0 治理与基线

## 1. 目标

建立可审查、可离线运行的跨语言兼容基线，覆盖历史裁剪、Prompt 消息顺序、SQLite 核心数据和错误迁移映射，并在 GitHub Actions 中执行 `compat` Profile。

## 2. 非目标

- 不迁移 Tool、Memory、MCP、渠道、流式输出或完整 Python Prompt Block。
- 不在 CI 中启动 Python 仓库或访问真实模型。
- 不新增第六个 Maven 模块，不改变五模块生产依赖方向。
- 不把 Python 动态时间、内部日志、FTS 或实现细节固化为 Java API。
- 不声称 Java 当前 HTTP 错误体是 Python 原样输出。

## 3. 设计

根目录 `testdata/golden` 是唯一共享夹具目录。Python 生成器只负责 `history`、`prompt` 和 `sqlite`；`errors` 是带 Python 来源证据的已批准迁移契约。`manifest.json` 记录 Python Commit、参考文件 Hash 和所有夹具 Hash。

测试归属：

- `agent-kernel`：历史 Golden。
- `agent-application`：Prompt 共同消息投影 Golden。
- `adapter-sqlite`：Python SessionStore Schema/数据 Golden。
- `agent-bootstrap`：公开 HTTP 错误迁移映射 Golden。

所有 Golden 测试使用 `@Tag("compat")`，默认构建排除，`-Pcompat` 明确包含。Maven 通过只读系统属性 `golden.root` 向各模块提供根目录，不复制夹具。

GitHub Actions 使用 JDK 21 和 Maven Wrapper，执行默认、`failure`、`compat` 三个 Job；不执行 `real-model-smoke`。

## 4. 数据来源与可信度

参考 Python 仓库可能存在与本次无关的未提交变更。生成前必须确认所引用源码文件本身干净，并在 Manifest 记录文件 Hash；本次参考 Commit 为 `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`。

夹具只能使用合成中文对话、固定时间和临时数据库，禁止读取真实 `.env`、`config.toml`、Workspace、Memory 或 Session。

## 5. 验收标准

- 规范明确格式、非确定字段和更新审批。
- 生成器在相同 Python Commit 和源码 Hash 下重复运行得到字节一致结果。
- 四类夹具均被 Manifest 覆盖且 Hash 可验证。
- Java 四个层级都存在 `compat` 测试，并能检测夹具或行为差异。
- 默认构建不执行 Golden，`compat` Profile 全部执行。
- GitHub Actions 包含默认、失败和兼容门禁。
- 不新增生产依赖、真实数据、密钥或网络模型调用。
