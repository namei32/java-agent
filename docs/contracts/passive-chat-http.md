# 被动聊天 HTTP 契约

本文档固定被动聊天 MVP 的外部 HTTP 契约，实现依据见 [被动聊天 MVP 设计](../specs/2026-07-12-passive-chat-mvp-design.md)。本契约不包含 Streaming、Tool、附件、多模态输入或 Provider Override。

## 1. 请求

```http
POST /api/v1/chat
Content-Type: application/json
X-Request-Id: local-demo-1
```

```json
{
  "sessionId": "demo-session",
  "message": "你好"
}
```

字段规则：

| 字段 | 规则 |
|---|---|
| `sessionId` | 必填；1–128 个字符；只允许 ASCII 字母、数字、`-`、`_`；不做路径解释 |
| `message` | 必填；使用 Unicode `strip` 去除首尾空白后为 1–32,000 个字符 |
| 请求体 | JSON；最多 65,536 字节，包括没有可信 `Content-Length` 的请求 |

客户端不得提交角色、System Prompt、模型、提供方、Temperature、Workspace 或数据库路径。未知业务字段不构成受支持的扩展点。

## 2. 成功响应

状态码：`200 OK`

```json
{
  "sessionId": "demo-session",
  "message": {
    "role": "assistant",
    "content": "你好，有什么可以帮你？"
  }
}
```

`message.role` 固定为 `assistant`。响应不包含 Spring AI 类型、SQLite 行 ID、Prompt 或提供方原始载荷。

## 3. 请求 ID

每个响应，包括成功、校验失败、超限和内部错误，都包含 `X-Request-Id`。

入站值满足 `[A-Za-z0-9._-]{1,128}` 时原样返回；缺失或非法时由服务器生成新的 UUID。日志使用同一个请求 ID 进行关联。

## 4. 错误响应

错误采用 RFC 9457 `application/problem+json`。常规错误包含稳定的 `type`、`title`、`status`、`detail` 和 `instance`；请求体超限响应由前置过滤器生成固定结构，不包含 `instance`。错误体不得包含堆栈、异常消息、API Key、Prompt、消息正文或提供方原始响应。

### 400 请求参数无效

字段校验失败：

```json
{
  "type": "about:blank",
  "title": "请求参数无效",
  "status": 400,
  "detail": "请求参数无效",
  "instance": "/api/v1/chat"
}
```

JSON 无法解析时，`title` 和 `detail` 为 `JSON 格式无效`。

### 413 请求体过大

```json
{
  "type": "about:blank",
  "title": "请求体过大",
  "status": 413,
  "detail": "请求体过大"
}
```

### 500 内部错误

SQLite 读写错误：

```json
{
  "type": "about:blank",
  "title": "会话持久化失败",
  "status": 500,
  "detail": "会话持久化失败",
  "instance": "/api/v1/chat"
}
```

未分类错误的 `title` 和 `detail` 为 `内部服务错误`。

### 502 模型或 Tool Runtime 调用失败

提供方拒绝、上游错误、非法响应、空响应、审批/Ledger 不可用或副作用状态未知统一映射为下列安全响应。当前沿用既有标题，不暴露 Approval ID、Fingerprint、Arguments、Actor、幂等键、Ledger 状态或内部异常：

```json
{
  "type": "about:blank",
  "title": "模型调用失败",
  "status": 502,
  "detail": "模型调用失败",
  "instance": "/api/v1/chat"
}
```

### 504 请求执行超时

模型调用超时或会话锁等待超时统一映射为：

```json
{
  "type": "about:blank",
  "title": "请求执行超时",
  "status": 504,
  "detail": "请求执行超时",
  "instance": "/api/v1/chat"
}
```

未映射的 URL 返回 `404` 和 `资源不存在`，且不会被转换成 `500`。
