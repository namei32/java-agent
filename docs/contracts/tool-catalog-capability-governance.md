# Tool Catalog 与 Capability 治理契约

- 状态：已批准，B1 已实现并验证
- 契约版本：1
- 日期：2026-07-18
- 阶段：R11 / B1
- Python 证据：`agent/tools/registry.py`、`agent/tools/tool_search.py`、`agent/tool_runtime.py`、`agent/mcp/registry.py`（基线 `b65a543`）
- 前置契约：[核心消息、生命周期与 Tool 契约](core-message-lifecycle-tool.md)、[Tool Runtime 安全契约](tool-runtime-safety.md)、[Tool 审批、副作用、幂等与沙箱安全契约](tool-approval-side-effect-safety.md)

> B1 只增加只读、按 Turn 生效的 Tool Catalog 与 `tool_search`。它不改变任何 Tool 的风险、执行边界、审批、Ledger、MCP 网络模式或默认部署开关，更不授权真实副作用工具。

## 1. 目的与安全差异

Python Registry 能把所有已注册工具建立索引，保留常驻工具，并在模型调用 `tool_search` 后将匹配工具加入该轮后续模型请求。Java 现有 Registry 只有静态全量列表；虽然已有严格 Approval/Ledger Framework，但模型既不能发现 deferred 工具，也不能把“发现”与“可执行”区分开。

Java 保留 Python 的目录—检索—本轮解锁投影，同时作出以下安全差异：

- 风险只能是严格枚举 `READ_ONLY`、`WRITE`、`EXTERNAL_SIDE_EFFECT`；不接受 Python 历史字符串作为运行时授权。
- Catalog 的来源、风险、版本、可见性、搜索提示和执行边界都由受信 Bootstrap/Adapter 注册；模型的查询和结果都不能修改它们。
- 搜索结果只影响当前 Turn 的下一个及后续模型请求；新 Turn 必须从常驻可见集重新开始。
- 搜索、解锁和 Schema 可见不等于审批、执行授权或外部连接。所有现有 Runtime Policy、Schema、预算、取消、Approval Gate 与 Ledger 检查仍在真正调用处执行。

## 2. 注册元数据

每个注册项由不可变的 `ToolDefinition` 与下列 Java-owned 元数据组成：

| 字段 | 规则 |
| --- | --- |
| `visibility` | 只能是 `ALWAYS_ON` 或 `DEFERRED`。`ALWAYS_ON` 每轮可见，`DEFERRED` 只能经成功搜索后本轮可见。 |
| `sourceKind` | `BUILTIN`、`MCP` 或后续明确批准的来源；不是模型字段。 |
| `sourceId` | 内置工具为空；外部来源为非秘密、稳定、受校验的来源标识。 |
| `searchHints` | 受信、非秘密的附加检索词；仅提高召回，不能改变排序以外的安全决策。 |
| `definition` | 名称、描述、Schema、风险和版本继续使用既有不可变定义。 |

`tool_search` 是保留名称。Catalog 非空且存在至少一个可发现 `DEFERRED` 项时，Runtime 自行投放它；外部注册同名工具、重复名称、非法来源或不合法 Schema 必须在启动时 Fail Closed。

## 3. 可见性状态机

```text
新 Turn
  -> ALWAYS_ON 可见
  -> tool_search 成功
  -> 命中且策略允许的 DEFERRED 项加入当前 Turn
  -> 下一次模型请求携带 ALWAYS_ON + 本轮已解锁项
  -> Turn 完成、失败或取消
  -> 丢弃本轮可见集
```

规则：

1. `DISABLED` 模式没有 Tool Definition，也没有 `tool_search`。
2. `READ_ONLY` 模式只能注册和解锁 `READ_ONLY` 项；非只读项在启动时拒绝。
3. `APPROVAL_REQUIRED` 的非只读项即使被搜索到，仍必须经过现有整批预检、审批、一次性消费和 Ledger；B1 不提供可用人类审批端口或生产 Ledger。
4. 未可见、未命中、被运行模式排除或 Schema 无效的调用，均按既有安全“工具不可用/参数无效”投影处理，不泄漏隐藏 Catalog、来源配置、文件路径、命令、密钥或异常正文。
5. 取消、超时、模型失败、提交失败和新 Turn 都不能保留解锁状态。

## 4. `tool_search` 协议

输入固定为：

```json
{
  "query": "用户需要的能力描述或 select:精确工具名",
  "top_k": 5
}
```

- `query` 去除首尾空白后不能为空；`top_k` 取 `1..10`，超出范围按 Schema 拒绝，不作静默放宽。
- `select:name[,name...]` 仅精确选择当前可发现、运行模式允许且尚未可见的名称。
- 普通查询以 Unicode 小写、空白分词和 CJK 单字/双字 token 进行确定性匹配；按匹配分数降序、名称升序打破平局。不能依赖网络、模型或平台 Locale。
- 结果是稳定 JSON，只包含 `matched`（名称、摘要、风险、来源种类、是否常驻）、`unlocked`、`already_loaded` 与受限提示。结果不含完整来源配置、参数原文、审批摘要或执行结果。
- `matched` 中每个工具的完整 Schema 由下一次 `ChatModelRequest` 的 definitions 投放；同一次模型响应不得执行刚刚解锁但此前不可见的工具。

## 5. 版本化 Fixture 与测试

新增 Java-owned `testdata/golden/tools/tool-catalog-v1.json`。生产 Registry、Tool Loop 和 Adapter 必须消费 Fixture，至少覆盖：

- 常驻与 deferred 初始可见性；
- 中文/英文检索、稳定排序、`select:`、重复选择、空查询和边界 `top_k`；
- 只在下一次模型请求加入 Schema、Turn 结束后重置；
- MCP 来源投影；
- `DISABLED`/`READ_ONLY`/`APPROVAL_REQUIRED` 的可见性边界；
- 取消、失败、非法调用和隐藏定义零泄漏。

测试只使用内存 Tool、固定 Model、临时 SQLite 和 Java Reference MCP Server；禁止真实 MCP、网络、Workspace、Shell、消息发送或生产 Secret。

## 6. 后续 R11 门禁

B1 完成后，R11 仍未完成。后续必须分别冻结并批准：

1. 可认证的 Approval Inbox、Pending Turn 加密持久化、恢复/撤回与控制面身份 Contract；
2. 生产 Durable Approval/Audit/Idempotency Ledger 的 SQLite Schema、迁移和回退 Contract；
3. 每一种真实副作用 Tool 的 Capability Contract（最小权限、摘要、幂等键、`UNKNOWN`、沙箱与 Smoke）；
4. MCP 非只读、远程 HTTP、OAuth、Resources/Prompts/动态管理等独立边界。

在这些契约与对应门禁完成前，生产继续使用 `DenyAllApprovalPort`、`SideEffectLedger.unavailable()`，部署模板继续 `AGENT_TOOL_MODE=DISABLED`，且没有任何真实副作用 Tool。
