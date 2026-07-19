# Python/Java 配置兼容契约

- 状态：已批准
- 契约版本：1
- 批准日期：2026-07-13
- Python 参考 Commit：`b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Python 基准：`agent/config.py`、`agent/config_models.py`、`config.example.toml`

## 1. 目的

本契约定义 Python `config.toml` 与 Java 启动配置之间的兼容边界。目标是让现有用户可以在不重写原配置、不泄露密钥、不意外切换真实工作区的前提下，用 Java 启动已经迁移的能力。

兼容表示：Java 能读取 Python 配置文档，按明确优先级解析已迁移字段，接受并保留尚未迁移或未知字段，同时对有意差异给出稳定诊断。它不表示 Java 已经实现配置中声明的全部 Python 能力。

## 2. 运行模式与配置文件定位

Java 保留两种启动模式：

1. **环境变量模式**：未显式指定配置文件，且当前目录没有 `config.toml`。继续使用现有 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL` 和 `AKASHIC_WORKSPACE`。
2. **TOML 兼容模式**：显式指定配置文件，或者当前目录存在 `config.toml`。Java 只读解析 TOML，并把已迁移字段投影为运行时配置。

配置文件定位顺序固定为：

1. Java 命令行 `--agent.config-file=/absolute/or/relative/path.toml`。
2. 环境变量 `NAMEI_CONFIG_FILE`。
3. 当前工作目录下存在的 `./config.toml`。
4. 否则进入环境变量模式。

显式指定的文件不存在、不是普通文件或扩展名不是 `.toml` 时必须启动失败。默认 `./config.toml` 不存在时不得失败。

相对路径以启动进程的当前工作目录为基准。日志和错误可以显示规范化配置路径，但不得打印配置正文。

## 3. 配置来源优先级

对已迁移字段，优先级从高到低固定为：

1. Java 已公开的环境变量覆盖值。
2. Python 现代分块字段。
3. Python 根级旧字段。
4. Provider 预设或契约默认值。

空白环境变量视为未提供。现代字符串字段为空字符串时，按 Python 当前 `or` 语义回退到根级旧字段；数值和布尔值不得用字符串真假规则转换。

Spring Boot 内部属性名不是本契约的跨语言 API。测试可以注入这些属性，用户迁移文档不得要求了解 Spring AI 内部配置结构。

## 4. 第一阶段活动字段

第一阶段只把 Java 被动聊天 MVP 能消费的字段接入运行时。

| 语义 | Python 现代字段 | Python 根级旧字段 | Java 环境变量覆盖 | 规则 |
| --- | --- | --- | --- | --- |
| Provider 标识 | `llm.provider` | `provider` | 无 | TOML 模式必填；允许任意非空 OpenAI-compatible 标识 |
| 主模型 | `llm.main.model` | `model` | `OPENAI_MODEL` | 解析后必填 |
| API Key | `llm.main.api_key` | `api_key` | `OPENAI_API_KEY` | 解析后必填；只能进入敏感配置容器 |
| Base URL | `llm.main.base_url` | `base_url` | `OPENAI_BASE_URL` | 空值时使用 Provider 预设；最终必须是 HTTP(S) 绝对 URI |
| System Prompt | `agent.system_prompt` | `system_prompt` | 无 | TOML 模式缺省为 Python 值 `You are a helpful assistant.`；环境变量模式继续使用 Java 资源文件 |
| 历史消息数 | `agent.context.memory_window` | `memory_window` | 无 | 缺省 `40`；必须是非负整数；映射到 Java `max-messages` |

Java 的 `max-characters=100000` 是本地安全上限，没有 Python 对等字段，不写回 TOML，也不伪装成跨语言字段。

Java 模型/会话超时继续由 `AGENT_MODEL_TIMEOUT` 控制；它是 Java 运维字段，不属于 Python 配置投影。

## 5. Provider 预设

当 Base URL 在环境变量、现代字段和旧字段中都为空时，使用 Python 当前预设：

| Provider | Base URL |
| --- | --- |
| `deepseek` | `https://api.deepseek.com/v1` |
| `openai` | `https://api.openai.com/v1` |
| `qwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` |

未知 Provider 必须显式提供 Base URL。Provider 名称不得被静默改写，Java 仍通过 OpenAI-compatible 适配器调用。

## 6. 已识别但尚未激活的字段

以下 Python 区域在 TOML 语法正确时必须被接受，但第一阶段不得驱动 Java 行为：

- `llm.fast`、`llm.agent`、`llm.vl`。
- `llm.main.multimodal`、`thinking`、`enable_thinking`、`reasoning_effort` 和根级 `extra_body`。
- `agent.max_tokens`、`agent.max_iterations`、`agent.tools`、`agent.maintenance`、`agent.wiring`。
- `channels`、`plugins`、`memory`、`proactive`、`integrations`、`peer_agents`。
- Java 尚未迁移的其他 Python 表和字段。

Java 可以在配置检查报告中把它们标记为 `DEFERRED`，但不得因为这些字段存在而启动失败，也不得声称对应能力已启用。R10-P1
已经审计这些 Provider 字段的激活前置条件，见[受信 Provider Options 决策记录](../specs/2026-07-19-r10-provider-options-decision.md)；
在明确选择其运行语义并通过独立 Fixture 前，它们继续保持 `DEFERRED`。

Tool、Memory、渠道等能力迁移时，必须通过新的 Contract/Spec 把相应字段从 `DEFERRED` 提升为活动字段，并增加 Golden。

## 7. 环境变量展开

兼容语法为完整占位符 `${ENV_NAME}`，其中名称匹配字母、数字和下划线。

第一阶段只对活动的 API Key 字段执行 TOML 内占位符展开。规则如下：

1. `OPENAI_API_KEY` 覆盖值优先于 TOML。
2. TOML API Key 是 `${ENV_NAME}` 时，从进程环境读取。
3. 环境变量缺失或为空时，以 `CONFIG_ENV_UNRESOLVED` 启动失败。
4. 不支持在一个字符串中拼接多个占位符。
5. 不读取 Python 的 `~/.akashic/workspace/memory/<ENV_NAME>` 隐式密钥回退。
6. Java 不自动加载 `.env`；本地 Shell 或部署平台负责把它导出为进程环境。

第 3、5 条是经批准的安全差异：禁止把未展开占位符当作真实密钥发送，也禁止从记忆目录隐式读取凭证。

未来激活 Plugin 配置时，必须另行定义递归占位符行为；当前只保留原 TOML 节点，不解析或打印其中的值。

## 8. 类型、默认值与校验

Java 必须使用 TOML 原生类型，不得把字符串 `"false"` 当作布尔值，也不得把任意字符串静默转换为数字。

活动字段启动校验至少包括：

- Provider、Model、API Key 非空。
- Base URL 是带 Host 的 `http` 或 `https` 绝对 URI。
- `memory_window` 是大于等于零的整数。
- 配置文件是合法 UTF-8 TOML。

校验必须先收集可安全公开的问题，再统一失败；不得在一条错误修复后才逐个暴露下一个问题。API Key、Token、Secret、Prompt 正文和插件配置值不得进入异常、日志或 Actuator。

稳定诊断码：

| 诊断码 | 含义 |
| --- | --- |
| `CONFIG_FILE_NOT_FOUND` | 显式配置文件不存在 |
| `CONFIG_FILE_INVALID` | 路径、扩展名或文件类型非法 |
| `CONFIG_TOML_INVALID` | TOML 语法或 UTF-8 解码失败 |
| `CONFIG_TYPE_INVALID` | 活动字段类型错误 |
| `CONFIG_REQUIRED_MISSING` | 必填活动字段缺失 |
| `CONFIG_ENV_UNRESOLVED` | 必需环境变量占位符无法解析 |
| `CONFIG_URL_INVALID` | Base URL 非法 |

公开错误只包含诊断码和字段路径，不包含原值。

## 9. 未知字段与原文安全

第一阶段配置加载是只读的：

- Java 不修改、格式化、迁移或覆盖 `config.toml`。
- 未知表、未知字段、注释和顺序必须留在原文件中。
- 解析后的原始配置树只存在于 Bootstrap 配置边界，不进入 `agent-kernel`。
- 运行时只接收活动字段的类型化快照。
- 禁止把完整原始树序列化到日志、健康检查或错误响应。

未来如需自动写入，必须单独批准写入 Contract，先备份原文件，并证明未知字段、注释和顺序不会静默丢失。未达到该条件前只能提供差异预览，不能提供 `--write`。

## 10. 工作区安全差异

Python 默认工作区是 `~/.akashic/workspace`，Java 当前默认是 `./workspace`。本契约批准继续使用 Java 的安全默认，避免仅因读取 Python 配置就写入真实 Python 工作区。

只有显式设置 `AKASHIC_WORKSPACE` 或 Java `--agent.workspace=...` 才改变 Java 工作区。配置兼容检查不得创建工作区、数据库或其他文件。

在真实 Python 工作区副本完成演练前，Java 不得自动推导或切换到 Python 默认工作区。

## 11. 生命周期与动态变更

配置在启动时读取一次并形成不可变快照。修改 TOML 或环境变量后必须重启 Java；第一阶段不提供文件监听、热更新或远程配置中心。

启动后的配置对象不得保留可变 TOML Map 引用。涉及模型、数据库和 Prompt 的部分更新不得在运行时生效，避免产生半新半旧状态。

## 12. 配置检查与 Golden

实施阶段必须提供无副作用的配置检查入口。检查只输出：

- 配置模式与规范化路径。
- 活动字段的来源，例如 `ENV`、`TOML_MODERN`、`TOML_LEGACY`、`PRESET`、`DEFAULT`。
- 已激活、延后和未知字段路径。
- 稳定诊断码。

API Key 等敏感字段只能输出 `PRESENT`、`MISSING` 或 `UNRESOLVED`，不得输出值、长度、前后缀或 Hash。

Golden 至少覆盖：现代 DeepSeek 配置、根级旧配置、现代字段覆盖旧字段、Provider 预设、API Key 环境变量、未展开变量、Deferred/未知字段、错误类型和非法 URL。

Golden 更新遵循 [Python/Java Golden Test 夹具规范](golden-test-fixtures.md)，不得因为 Java 测试失败而直接重录。

## 13. DeepSeek 兼容示例

```toml
[llm]
provider = "deepseek"

[llm.main]
model = "deepseek-chat"
api_key = "${DEEPSEEK_API_KEY}"
base_url = "https://api.deepseek.com/v1"

[agent]
system_prompt = "You are a helpful assistant."

[agent.context]
memory_window = 40
```

启动前由部署环境提供 `DEEPSEEK_API_KEY`。如果同时设置 `OPENAI_API_KEY`，后者作为 Java 明确覆盖值生效；变量名中的 `OPENAI` 表示当前协议适配器，不限制实际 Provider。

## 14. 变更审批

以下变化必须修改本契约并明确批准：

- 改变来源优先级、默认工作区或配置文件定位。
- 激活新的 Python 字段或停止支持旧别名。
- 新增配置写入、热更新或远程配置源。
- 改变密钥解析、日志脱敏或错误诊断。
- 将未知字段从“接受并保留”改为拒绝。

普通实现重构不得改变本契约可观察行为。
