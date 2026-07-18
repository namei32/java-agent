# R12-S3 Plugin 生命周期只读映射契约

- 阶段：R12-S3
- 状态：已实现并验证；默认 `DISABLED`
- 契约版本：1
- 日期：2026-07-19
- Python 证据：`agent/plugins/registry.py`、`agent/plugins/decorators.py`、`agent/plugins/manager.py`、`agent/lifecycle/*`（基线 `b65a543`）
- 前置：[R7 插件扩展运行时契约](plugin-extension-runtime.md)
- 关联 ADR：[ADR-0023：以 API v2 增加只读 Plugin Lifecycle Tap](../adr/0023-add-read-only-plugin-lifecycle-tap-v2.md)

## 1. 目标与冻结边界

Python Plugin 可以接收 `before_turn`、`before_reasoning`、`prompt_render`、`before_step`、`after_step`、
`after_reasoning`、`after_turn`、`before_tool_call`、`after_tool_result` 等生命周期上下文，其中多数 Gate 可以改变
后续业务。Java R7 当前只把安全摘要发送为 `TURN_TAP` 或 `TOOL_TAP`；外部 Plugin 无法可靠区分 Phase。

本切片只增加一个 opt-in 的 API v2 `LIFECYCLE_TAP`。它让受信 Java Service 或显式配置的 stdio Plugin 接收
固定 Lifecycle Phase、安全 Hash、Outcome、可选稳定码和零或正的 duration；它永远是异步 Tap，返回值、异常、超时、
拒绝或关闭都不得改变 Chat、Prompt、Tool、Approval、Ledger、Session、Channel、Message 或 Proactive 的业务结果。

不实现 Python Gate、Prompt Render、Before/After Step、Plugin Tool、Tool Hook、Channel、KV/Workspace、Plugin 配置
文件、Python import、动态发现、远程 Plugin、MCP、Shell、网络或真实副作用。API v1 Plugin 不会收到新事件，也不
需要修改。

## 2. API 与 Capability

`PluginManifest.apiVersion=1` 维持当前能力集和 Wire 行为。`apiVersion=2` 才能声明 `LIFECYCLE_TAP`；v1 声明它、
v2 声明未知或重复 Capability、或任意版本不匹配均以既有稳定 Plugin Contract 码 Fail Closed。原有 `TURN_TAP`、
`TOOL_TAP` 与 `PROACTIVE_TAP` 不获得 Phase 字段，防止静默改变 v1 stdio 协议。

`LIFECYCLE_TAP` 的 stdio 方法固定为 `lifecycle.tap`。请求 params 只包含：

```text
phase            固定 enum
referenceHash    64 位小写 SHA-256 hex
outcome          ACCEPTED | COMPLETED | FAILED
durationMillis   0..86,400,000
code             可选稳定 Plugin 码
```

不得发送 sessionId、conversationId、callId、toolName、arguments、result、prompt、消息正文、路径、异常、配置、
环境变量或 Secret。响应必须仍为严格的 `{accepted: true}`；任何协议/进程/超时失败只隔离该 Plugin。

外部 stdio Plugin 只有在 `agent.plugins.mode=EXTERNAL_STDIO` 且该条目显式声明 `api-version: 2` 时才能使用
`LIFECYCLE_TAP`；省略字段固定回落到 API v1。该配置仅选择既有受限 stdio Bridge，不会扫描目录、读取 Python
Manifest 或启动默认 Plugin。Manifest/API/Capability 在 Properties 构造时、进程工厂之前验证；不合法配置不会
创建 stdio 子进程。

## 3. 固定映射

只有下表 Java 事件产生 v2 Lifecycle Tap；映射之外的事件绝不伪装为 Python Phase：

| Java `TurnEventType` | `PluginLifecyclePhase` | Capability | Outcome |
| --- | --- | --- | --- |
| `TURN_STARTED` | `BEFORE_TURN` | `LIFECYCLE_TAP` | `ACCEPTED` |
| `MODEL_REQUESTED` | `BEFORE_REASONING` | `LIFECYCLE_TAP` | `ACCEPTED` |
| `MODEL_COMPLETED` | `AFTER_REASONING` | `LIFECYCLE_TAP` | `COMPLETED` 或 `FAILED` |
| `TOOL_CALL_STARTED` | `BEFORE_TOOL_CALL` | `LIFECYCLE_TAP` | `ACCEPTED` |
| `TOOL_CALL_COMPLETED` | `AFTER_TOOL_RESULT` | `LIFECYCLE_TAP` | `COMPLETED` 或 `FAILED` |
| `TURN_COMMITTED` | `AFTER_TURN` | `LIFECYCLE_TAP` | `COMPLETED` |
| `TURN_FAILED` | `AFTER_TURN` | `LIFECYCLE_TAP` | `FAILED` |

`PROMPT_RENDER`、`BEFORE_STEP`、`AFTER_STEP`、`PRE_TOOL`、Approval、Side Effect、Outbound Message 和 Proactive
不会被映射为 Lifecycle Phase。它们已有或未来的独立 Tap Contract 不改变本表。

同一 Java 生命周期事件最多向一个绑定的 v2 `LIFECYCLE_TAP` 发布一次。v1 的 `TURN_TAP`/`TOOL_TAP` 投影保持
现有次数、Hash 公式和行为；v2 元数据 Tap 不会使 v1 Tap 重复。

## 4. 默认关闭与验收

`AGENT_PLUGINS_MODE=DISABLED` 时零 ServiceLoader、零线程、零 stdio 进程、零 Lifecycle Tap。启用仍要求全局
`agent.tools.mode=READ_ONLY` 和已有 R7 的受信配置边界；新 Capability 不改变这个条件。

Java-owned `plugins/lifecycle-tap-v2.json` Fixture 必须固定 API v1/v2 Capability 约束、每个映射、未映射事件、
Phase/Hash/Outcome/稳定码脱敏、stdio `lifecycle.tap` Wire、超时/协议失败隔离和 Disabled 零 I/O。测试只使用 Java
Fake Plugin/Transport；不导入 Python、不读取真实 Plugin 目录、不访问网络或执行外部生产 Plugin。

实现已由默认 `clean verify`、`-Pfailure verify` 与 `-Pcompat verify` 验证；后两者分别覆盖失败隔离和 Fixture
内容哈希/版本化语义。
