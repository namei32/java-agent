# Python/Java Golden Test 夹具规范

- 状态：已批准
- 格式版本：1
- 生效日期：2026-07-13

## 1. 目的

Golden Test 使用已经审查并提交到仓库的确定性夹具，验证 Java 在已迁移范围内仍保持 Python 参考行为或已批准迁移契约。它不替代 Java 单元测试，也不把 Python 的全部内部实现变成永久 API。

CI 只读取已提交夹具，不克隆或执行 Python 仓库。重新生成夹具是显式维护操作。

## 2. 目录与文件

```text
testdata/golden/
├── manifest.json
├── configuration/
│   ├── config-resolution.json
│   └── config-validation.json
├── context/read-only-context-memory.json
├── memory/java-native-memory.json
├── mcp/java-mcp-client.json
├── proactive/
│   ├── proactive-runtime-v1.json
│   ├── read-only-local-proactive-job-inspection-v1.json
│   ├── r14-proactive-peer-automation-v1.json
│   └── r14-read-only-proactive-decision-v1.json
├── message-bus/
│   ├── versioned-channel-message.json
│   └── provider-streaming-cli.json
├── history/session-history.json
├── prompt/message-envelope.json
├── provider/r10-provider-failure-v1.json
├── provider/r10-context-limit-recovery-v1.json
├── provider/r10-provider-cache-usage-v1.json
├── provider/r10-provider-options-v1.json
├── provider/r10-reasoning-tool-continuation-v1.json
├── control-plane/r13-read-only-control-index-v1.json
├── control-plane/r13-terminal-history-catalog-v1.json
├── sqlite/session-store.json
├── tools/
│   ├── message-envelope.json
│   ├── minimal-loop.json
│   ├── runtime-safety.json
│   ├── approval-side-effects.json
│   ├── tool-catalog-v1.json
│   ├── conversation-evidence-v1.json
│   ├── read-only-memory-recall-v1.json
│   ├── approval-inbox-v1.json
│   └── pending-operation-v1.json
└── errors/http-error-mapping.json
```

所有文件必须是 UTF-8 JSON，使用两个空格缩进、键名排序，并以换行结束。禁止提交二进制 SQLite、真实工作区数据、密钥、用户记忆或真实对话。

`manifest.json` 固定以下字段：

```json
{
  "formatVersion": 1,
  "pythonBaseline": {
    "commit": "40 位 Git SHA",
    "repository": "namei32/akashic-agent",
    "sourceFiles": {
      "relative/path.py": "sha256"
    }
  },
  "fixtures": [
    {
      "id": "history/session-history",
      "path": "history/session-history.json",
      "sha256": "夹具内容 sha256",
      "source": "python-reference"
    }
  ]
}
```

Manifest 当前登记 42 个业务夹具。普通业务夹具固定：

- `formatVersion`：当前为 `1`。
- `suite`：所属能力的稳定套件名。
- `source`：`python-reference`、`migration-contract` 或 `java-contract`。
- `pythonEvidence` 或 `contractEvidence`：前者记录 Python 参考类、函数和源码路径，后者记录已批准 Java Contract；二者按 `source` 选择，不能伪造 Python 等价行为。
- `normalization`：应用过的规范化规则；没有则为空数组。
- `cases`：稳定的 `id`、输入和预期输出。

`message-bus/provider-streaming-cli.json` 是批准的分组例外：它以 `kernelCases`、`applicationCases` 和 `cliCases` 分别保存 6、9、6 个 Case，并以 `limits` 固定夹具内的事件与 Code Point 边界。Validator 必须跨三个数组检查全局 Case ID 唯一和 `input`/`expected` 完整性；其他夹具仍必须提供单一 `cases` 及 `normalization`，不能借此放宽。

未知顶层字段允许保留，但测试必须拒绝不支持的 `formatVersion`、重复 Case ID、缺失必需字段和 Manifest 校验和不一致。

## 3. 当前行为投影

### 3.1 配置

`configuration/config-resolution.json` 由 Python `agent.config.load_config` 真实生成，固定现代字段、根级旧字段、优先级、空字符串回退、Provider 预设、环境变量和 Deferred/未知字段的共同活动投影。API Key 只保留 `PRESENT`、`MISSING` 或 `UNRESOLVED` 状态；夹具中的 `__GOLDEN_SECRET__` 是不可用的测试哨兵值。

`configuration/config-validation.json` 的来源是已批准迁移契约，固定 Java 相对 Python 更严格的缺失字段、未展开密钥、原生类型、URL 和 TOML 语法诊断。禁止把这些有意安全差异描述为 Python 原始输出。

### 3.2 历史

基准来自 Python `session.manager.Session.get_history`。本阶段只比较 Java MVP 能表达的普通 `user/assistant` 文本消息：空历史、完整多轮、最新完整轮次和孤立 Assistant。Tool、Proactive、多模态、`reasoning_content` 和 Context Frame 留待相应能力迁移。

字符上限是 Java MVP 的本地保护语义，不属于当前 Python Golden，由 Java 单元测试负责。

### 3.3 Prompt

基准来自 Python `agent.context.MessageEnvelopeBuilder`。当前只比较共同投影：

```text
system -> history -> current user
```

Python 当前用户消息的时间信封在生成时使用固定时间，并在共同投影中移除；完整 Prompt Block、Context Frame、渠道增强、多模态和动态时间将在 R2 能力对齐时增加独立夹具。

### 3.4 SQLite

基准由 Python `session.store.SessionStore` 创建临时数据库后规范化为 JSON。只固化核心表列、默认值、唯一约束、Session 游标和消息顺序。FTS 虚表、触发器与 SQLite 内部对象不属于当前 Java MVP。

共同投影中的 Offset 时间使用 ISO-8601，并始终保留秒，例如 `2026-07-13T08:01:00+08:00`。小数秒仅在非零时保留。

### 3.5 错误映射

Python 没有与 Java 被动聊天 MVP 相同的 HTTP 入口。因此该夹具的 `source` 必须是 `migration-contract`：Python 的 Provider Timeout、Provider Failure 和 SQLite Failure 是迁移来源，HTTP Status、Title、Detail 与 Instance 由 Java [被动聊天 HTTP 契约](passive-chat-http.md)批准。

禁止把这一夹具描述为逐字 Python HTTP 输出。夹具同时固定 Approval/Ledger 不可用和副作用状态未知沿用安全 `502` 的迁移契约，禁止暴露审批、参数或 Ledger 细节。

### 3.6 Tool

`tools/message-envelope.json` 由 Python `agent.tool_runtime` 生产 helper 真实生成，固定 Function Schema、Assistant Tool Call、Arguments JSON 和 Tool Result 的 OpenAI-compatible 消息格式。跨语言比较前只把 Arguments JSON 字符串解析为 Object，不删除字段。

`tools/minimal-loop.json` 的来源是已批准迁移契约，固定直接回答、单/多工具顺序、未知工具、工具异常、非法模型响应、迭代上限、最终提交和安全生命周期轨迹。Python `AgentReasoner.run`、`ToolRegistry.execute` 和生命周期事件提供迁移证据；Java 不额外生成超限总结、暂不持久化 `tool_chain` 是明确批准差异。

Java `compat` 测试必须把其中 7 个 Case 逐一交给生产 `ChatService`、`ToolLoop` 和 `ToolRegistry` 执行，并比较结果、提交决策、尝试顺序和完整安全生命周期轨迹；只检查 JSON 字段存在不算通过。

`tools/runtime-safety.json` 固定 Tool 模式、预算、Schema、Arguments/Result 边界、超时、并发许可与取消场景。`tools/approval-side-effects.json` 使用 Python 生产 Registry/Hook helper 固定风险标签和前置拒绝投影，并以 `migration-contract` 固定 Java 的整批零执行、审批绑定、一次性消费、幂等重放、`UNKNOWN` 停机、生命周期和模式可见性。`tools/tool-catalog-v1.json` 是 Java-owned Fixture，固定常驻/延迟可见性、CJK/精确检索、同批隔离、下一请求 Schema、模式关闭和零执行。`tools/approval-inbox-v1.json` 是 Java-owned 的 29 场景 Fixture，固定独立 SQLite、随机引用、一次性决定、到期/容量、原始绑定标识哈希化、默认关闭、Loopback 投影和“决定不执行工具”的 B2a 边界。`tools/pending-operation-v1.json` 是 Java-owned 的 54 场景 Fixture：除 Pending 状态、AES-256-GCM、篡改、迁移与原子写入外，固定 Approval `CONSUMED` + Operation `CONSUMING` + 唯一 Reservation 的同库原子性、到期、并发/重放失败关闭、无执行 Ledger 终态、Session Cursor 条件追加、初始 Session Anchor 原子写入、安全 Assistant Result 投影的 Anchor 条件提交、不可恢复 Cursor 拒绝，以及测试专用 Fake Capability 的成功/`UNKNOWN`/`COMMIT_UNREPORTED` 零重放演练；它不授予生产恢复编排或 Tool 执行权。Java 必须把这些 Case 交给生产 Registry/Coordinator/Loop、Inbox/SQLite/Control 或 Capsule 组件执行；测试 Fake 只能提供确定性 Model、Invoker 与本地 Key Provider，不能替代被测控制流。

`control-plane/pending-recovery-control-v1.json` 是 Java-owned 的 24 场景运行时 Fixture：固定默认/仅 Loopback 时均不映射、严格无 Body/Query 的不透明 `operationRef`、Resume/Cancel 的零执行条件、`UNKNOWN`/`COMMIT_UNREPORTED` 的停机，以及 Status 的稳定脱敏投影。兼容性测试逐 Case 绑定生产实现或明确聚焦测试所有者；它不创建 Worker、自动 Resume、Tool/Chat 生产器、新 Token 或任何真实副作用。

### 3.7 Context 与 Java 原生 Memory

`context/read-only-context-memory.json` 固定 Python 只读 Markdown Context 的共同投影。`memory/java-native-memory.json` 是 Java-owned Contract，固定版本化 Schema、显式写/列/删、Embedding、cosine/Hotness/Scope 检索、预算、排序、幂等和失败隔离；它不读取或迁移旧 Python `memory2.db`。

### 3.8 MCP

`mcp/java-mcp-client.json` 是 Java-owned Contract，固定静态 stdio 配置、发现分页、稳定名称、安全 Schema、只读调用、取消、Stale、单次有界重连和关闭语义。夹具不授权真实 MCP Server、网络 Transport、动态 Catalog 或副作用工具。

### 3.9 Message Runtime、Streaming 与 CLI

`message-bus/versioned-channel-message.json` 固定 40 个版本化入站/出站、Route、严格 Sequence、唯一终态、取消和背压 Case。`message-bus/provider-streaming-cli.json` 固定 21 个 Kernel/Application/CLI Case，覆盖流预算、Tool Loop Delta、权威完成快照、取消/失败提交隔离、CLI 输入输出与生命周期。两者均由生产 Java 路径消费，不把本地 HTTP Stub 描述为真实外部 Provider 验收。

### 3.10 Provider 失败分类、上下文恢复与缓存观察

`provider/r10-provider-failure-v1.json` 是 Java-owned Fixture，固定同步/流式的内容安全、上下文超限、Timeout 优先、
未知上游失败、嵌套 Cause 与稳定 Channel `retryable` 投影。它只断言受限 marker 分类和固定公开结果，不保存或比较
Provider Error Body、Prompt、reasoning、cache usage、密钥或真实模型输出。

`provider/r10-context-limit-recovery-v1.json` 是 Java-owned P3 Contract Fixture，固定默认关闭与 `SAFE_LOCAL` 的
Prompt/History 候选顺序和去重；生产 Policy 必须逐 Case 消费。它不包含真实 Prompt、历史正文、Provider Body、
重试 trace 或 Tool/Side Effect 执行结果。

`provider/r10-provider-cache-usage-v1.json` 是 Java-owned P4 Contract Fixture，固定零模型响应、无有效 cache pair、
零命中、跨 Tool Loop 的有效 pair 累加，以及缺失 pair 不伪造为零且不阻断其余有效 pair。它只含三项聚合整数；生产
Adapter/Turn collector 不得读取 native Usage、缓存键、模型/会话/请求标识、内容或 Provider payload。

`provider/r10-provider-options-v1.json` 是 Java-owned P1 Contract Fixture，固定默认零注入、DeepSeek effort、
DeepSeek/DashScope 的固定 thinking body、任何 Tool Schema 时的显式清除，以及非法 Profile/effort 组合拒绝。`null`
代表根本未发送 Provider body，`{}` 只代表含 Tool Schema 时刻意清空已有 body；两者不能混淆。它不接受 URL 推断、
Python TOML 或任意 `extra_body`。

`provider/r10-reasoning-tool-continuation-v1.json` 是 Java-owned P2 Contract Fixture，固定默认零回放、显式
`DEEPSEEK` + `SAFE_LOCAL` 的 Tool thinking 放行、16,384 code-point 边界、超长丢弃、最终文本零回放及
`DASHSCOPE` + `SAFE_LOCAL` 的启动期拒绝。它不保存 reasoning；同步/流式 Adapter 与 ToolLoop 另验证只回放紧随的
assistant Tool Call、分段 `<think>` 不发布到 observer、取消后零续轮/零持久化。

`control-plane/r13-read-only-control-index-v1.json` 是 Java-owned R13-C0 Contract Fixture，固定未来 Loopback 只读
索引的默认零映射、Bearer、最小渠道/活跃 Turn 投影、稳定排序、20/50 分页、opaque cursor、快照降级和敏感字段禁令。
它不创建 Controller、Route、历史查询、SQLite 查询、前端、SSE 或写入；其 `compat` consumer 只验证该冻结边界。

`control-plane/r13-terminal-history-catalog-v1.json` 是 Java-owned R13-C2-A Contract Fixture，固定默认关闭的
Loopback 内存终态目录、Bearer、最小 metadata 投影、稳定排序、20/50 分页、actor-bound reference/一次性 cursor、
快照降级及正文/身份/详情 Route 禁令。它只读取受 TTL/容量限制的 Registry Tombstone，不读取 Session、SQLite、
Memory 或真实渠道。

`control-plane/r13-history-detail-v1.json` 是 Java-owned R13-C2-B Contract Fixture，固定 31 个当前 Scope 的零正文
详情 Case：Servlet/Loopback 激活、严格 GET/query、actor/Scope、60 秒一次性 Ref/Cursor、24 小时 retention、角色/字段
白名单、1,024 candidate/20 item 上限、关闭和脱敏失败。它不含正文、Session、actor、路径、token、SQLite 路径或异常文本；
运行时只在临时 Java SQLite/Fake 验证，默认生产 Scope Resolver/Port 仍拒绝读取。

## 4. 非确定字段

| 字段 | 处理方式 |
| --- | --- |
| 当前时间、消息时间 | 注入固定时间；只在共同 Prompt 投影中移除时间信封 |
| UUID、Request ID | 固定输入；生成值不进入 Golden |
| 临时路径、端口 | 不写入夹具，或替换为语义占位符 |
| SQLite `rowid`、内部表顺序 | 不进入 Golden；查询显式排序 |
| JSON Object 键顺序 | 生成时排序；Array 顺序保持语义顺序 |
| 模型自由文本 | 不调用真实模型；使用固定预期回答或 Fake |
| 异常消息与堆栈 | 不进入 Golden，只比较稳定错误类别与公开字段 |
| 耗时、线程名、日志时间 | 不进入 Golden |

规范化必须最小化并写入 `normalization`；禁止使用“忽略整个响应”或删除业务字段的宽泛规则。

## 5. 生成与验证

从 Java 仓库根目录执行：

```bash
../akashic-agent/.venv/bin/python tools/golden/generate.py \
  --python-repo ../akashic-agent \
  --output testdata/golden
```

生成器必须：

1. 检查 Python 仓库和所需源码文件存在。
2. 记录 Python HEAD 和参考文件 SHA-256。
3. 在临时目录运行，不读取真实工作区。
4. 原子替换由其管理的夹具；不得修改错误迁移契约夹具。
5. 为生成器管理的 Python 参考夹具生成校验和；人工维护的迁移契约和 31 个 `java-contract` 夹具必须继续保留在最终 Manifest 中。

当前最终 Manifest 共登记 43 个夹具。Python 生成器不拥有 Java Memory、MCP、Provider 失败/受信 Options/有界 reasoning continuation/上下文恢复/缓存观察、两个 Message Bus、Telegram、可靠投递、Loopback 控制面、R13 C0 索引与 C2-A 内存终态目录、Plugin、Proactive、Cutover、Prompt 编排、Tool Catalog、Approval Inbox 和 Pending Operation 等 Java-owned Fixture；重新生成后，维护者必须保留这些条目并按实际内容同步 SHA-256，再运行 `compat`。禁止因生成器输出而静默移除 Java-owned Contract。

Java 验证命令：

```bash
./mvnw -Pcompat verify
```

## 6. 更新审批

Golden 不得因为测试失败而直接重录。更新 Pull Request 必须同时包含：

1. 变更原因：Python 基准更新、批准的 Contract 变更、新增 Case 或生成器修复。
2. 旧值与新值的语义差异。
3. 对 Java 实现、数据兼容和回退的影响。
4. 对应 Spec、Contract 或 ADR 链接。
5. 生成命令、Python Commit、Java `compat` 验证结果。

审批要求：至少一名了解迁移契约的 Reviewer 明确批准；涉及 SQLite 写入、公开 HTTP 字段或删除 Case 时，还必须由项目维护者批准。生成器、夹具和 Java 断言不得由一次未经审查的批量更新同时放宽。

紧急修复也必须保留差异和事后审批记录。CI 中禁止自动提交 Golden。
