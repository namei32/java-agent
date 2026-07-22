# 本地 Proactive Job 只读检视契约

- 阶段：R8 后续安全可观测性切片
- 状态：已实现并验证
- 日期：2026-07-19
- Python 参考：`agent/scheduler.py` 的 `list_schedules`
- 关联：[R8 主动运行时契约](proactive-runtime.md)、R11-B2c 候选审计
- 关联设计：[本地 Proactive Job 只读检视设计](../specs/2026-07-19-r8-read-only-proactive-job-inspection-design.md)

## 1. 目的与非等价声明

Python `list_schedules` 会返回任务名称、原始渠道/聊天标识、消息或 prompt 预览、时区、执行次数等信息。Java R8
没有这些可安全投影的数据，也不应借由只读 Tool 暴露 hash、目标、内容、数据库路径或渠道身份。

本契约定义 Java 专有的 `list_local_proactive_jobs`，用于检视**已在本进程中显式启动**的本地 R8 Runtime 的活动 Job。
它是一个有意更窄的安全替代，不能称为 Python `list_schedules` 的逐字或逐字段兼容，也不能替代创建、取消、投递、审批、
自动 Memory 或 R11-B2c 的副作用 Capability。

## 2. 默认关闭与装配条件

配置键 `agent.proactive-inspection.mode` 严格只接受 `DISABLED|ACTIVE_RUNTIME`，默认 `DISABLED`。

只有同时满足下列条件时，Tool 才注册为 Deferred Builtin：

1. `agent.tools.mode=READ_ONLY`；
2. `agent.proactive-inspection.mode=ACTIVE_RUNTIME`；
3. 已有 `ProactiveRuntime.active()` 为真，即 R8 `LOCAL_SQLITE` Runtime 已由其自身配置启动。

全局 `agent.tools.mode=DISABLED` 永远优先，零 Tool、零查询，并且不因检视配置创建 Workspace、SQLite、线程、随机 ID
或 Scheduler。若全局 Tool 已启用而检视显式为 `ACTIVE_RUNTIME`、但 Runtime 不活动，Bootstrap 必须失败关闭；检视 Tool
不得自行启动 Runtime 或打开数据库。`APPROVAL_REQUIRED` 不能替代 `READ_ONLY`，应以稳定配置错误拒绝。

## 3. Tool 表面与预算

Tool 名称固定为 `list_local_proactive_jobs`，版本固定为 `local-proactive-inspect-v1`，风险为 `READ_ONLY`，
仅经当前 Turn 的 `tool_search` 作为 Deferred Schema 暴露。

输入是严格 JSON Object：

```json
{"limit": 16}
```

`limit` 可省略，默认 `16`；只接受整数 `1..32`。拒绝额外字段、浮点、字符串数值、零、负数和超限值，并返回
`PROACTIVE_JOB_INSPECTION_INVALID_ARGUMENT`。缺少活动 Runtime、Port 异常、Port 返回超出请求上限或不安全投影时，返回
`PROACTIVE_JOB_INSPECTION_UNAVAILABLE`；不得泄露异常正文、数据库路径、配置或内部状态。

成功结果固定为：

```json
{
  "count": 1,
  "limit": 16,
  "jobs": [
    {
      "job_ref": "daily-summary",
      "schedule": "EVERY",
      "next_run_at": "2026-07-20T00:00:00Z",
      "every_seconds": 3600,
      "state": "SCHEDULED",
      "attempts": 0,
      "max_attempts": 3
    }
  ]
}
```

`AT` Job 不包含 `every_seconds`。结果只能包含 `SCHEDULED`、`CLAIMED`、`RUNNING`；按 `next_run_at ASC, job_ref ASC`
排序，最多为请求 `limit`。`SUCCEEDED`、`SKIPPED`、`FAILED`、`CANCELLED` 均不作为待检视的活动任务返回。
如果既有 Store 中的 `EVERY` 间隔不是正整数秒，Tool 必须返回 `PROACTIVE_JOB_INSPECTION_UNAVAILABLE`，不得截断、四舍五入
或伪造 `every_seconds`。

## 4. 投影与数据边界

Kernel Port 只能返回不可变 `ProactiveJobInspectionSnapshot`，其中字段为 `jobRef`、schedule kind、next run、可选周期、
state、attempts、maxAttempts。它没有 `targetHash`、`idempotencyKey`、owner、lease、revision、更新时间、数据库路径、
prompt、消息、渠道、chat ID、时区、运行计数或任何 Delivery/Memory/Approval 数据。

SQLite Adapter 只查询该投影需要的列，且只选择活动状态。Application Toolset 不接受或渲染 `ScheduledJob`；这确保未来
`ScheduledJob` 增加敏感字段时不会默认为模型可见。任何状态/间隔/计数不满足 R8 值对象约束的存储行必须使调用安全失败，
而非宽容地输出。

## 5. 禁止事项与验收

该 Tool 不写入、不 Claim、不恢复、不取消、不创建计划、不发送消息、不调用 Provider、MCP、Plugin、Workspace、Memory、
Approval、Pending Operation 或网络。它不读取 Python Scheduler/工作区，也不迁移旧 Python 数据。

版本化 Java-owned Fixture 必须至少覆盖默认零注册、活动 Runtime 的 Deferred 可见性、默认/最大 limit、非法参数、
端口不可用、终态排除、稳定排序与敏感字段不泄露。Kernel、SQLite、Application 与 Bootstrap 测试必须分别消费真实
生产边界；已通过 `clean verify`、`-Pfailure verify`、`-Pcompat verify` 三套门禁。
