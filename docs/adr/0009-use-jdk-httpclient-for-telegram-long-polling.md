# ADR-0009：Telegram 首渠道使用 JDK HttpClient 与 Bot API 长轮询

- 状态：已接受
- 日期：2026-07-16
- 阶段：R6.3
- 批准记录：用户于 2026-07-16 明确批准本 ADR 并要求开始实现
- 未授权范围：真实 Token、真实 Telegram 网络和真实用户数据
- 关联 Contract：[Telegram Channel Host 契约](../contracts/telegram-channel-host.md)
- 关联 Spec：[Telegram Channel Host 设计](../specs/2026-07-16-telegram-channel-host-design.md)
- 官方参考：[Telegram Bot API](https://core.telegram.org/bots/api)

## 背景

R6.1/R6.2 已经建立纯 JDK Message Contract、Provider Streaming、本地 CLI、取消和有界 Buffer。R6.3 需要首个真实外部渠道，但不能让渠道框架取得 Agent Loop、线程、重试、取消或提交控制权。

Python 使用 `python-telegram-bot`，并在同一模块承担长轮询、附件、命令、Markdown、实时消息编辑、主动发送和大量渠道特例。直接复制到 Java 会一次迁移过多能力，也会把 Python 的无界/隐式生命周期带回已经收紧的 Java Runtime。

Telegram 官方提供稳定的 HTTP Bot API。`getUpdates` 支持 Long Polling、offset 确认和 `allowed_updates`；`sendMessage` 支持纯文本回复。当前主线只需要私聊文本纵向切片，不需要 MTProto、Webhook、附件或富文本。

## 决策

1. R6.3 使用 JDK 21 `HttpClient` 直接调用 Telegram 官方 Bot API，不引入第三方 Telegram Java SDK。
2. 入站使用 `getUpdates` 长轮询；不实现 Webhook、公网监听或远程 TLS 入口。
3. 出站版本 1 只使用 `sendMessage` 纯文本，不设置 `parse_mode`，不使用 `editMessageText` 做 Delta 预览。
4. Channel Host、Bot API Client 和 Telegram Adapter 位于 `agent-bootstrap`；不新增 Maven 模块、部署单元或外部生产依赖。
5. Client 只负责一次请求的协议转换与安全错误映射；重试、取消、并发和关闭由项目 Adapter 显式控制。
6. 默认 Disabled 路径不读取 Token、不创建 Client、不启动 Worker。真实网络和 Token Smoke 保留独立授权。

## 理由

- JDK `HttpClient` 已提供连接/请求 Timeout、中断和 HTTPS；当前两种 Bot API 方法不需要 SDK 的对象体系。
- 项目可以精确限制响应字节、JSON 字段、重试次数、Thread 和 Shutdown，而不依赖第三方框架的隐藏 Executor 或自动重试。
- Long Polling 不需要公网域名、证书、Webhook Secret、反向代理或入站防火墙变更，符合单机渐进式重写。
- Fake Bot API Server 可以完整验证 URL、JSON、offset、429 和断线，不需要真实 Telegram 或 Token。
- 终态合并优先证明身份、路由、取消和 Agent 闭环；实时编辑属于展示优化，可在主线完成后独立扩展。

## 备选方案

| 方案 | 优点 | 拒绝/后置原因 |
| --- | --- | --- |
| 第三方 Telegram Java SDK | DTO、Handler 和重试封装较完整 | 没有必要的新依赖；可能引入隐藏线程、回调控制权和自动重试；版本 1 只用两个 HTTP 方法 |
| 复用 Python `python-telegram-bot` 进程 | 与旧实现最接近 | Java 主线仍依赖 Python Runtime/IPC，取消和 Message Contract 跨进程复杂化 |
| Telegram Webhook | 延迟低、无需持续 Poll | 需要公网 TLS、来源认证、重放防护和部署变更，超出当前授权 |
| MTProto Client | 能力最完整 | 协议和身份复杂度远超 Bot 私聊文本需求 |
| 同时实现 `editMessageText` 流式预览 | Telegram 体验更接近 CLI | 引入速率、消息 ID、编辑失败和最终分片纠正语义；作为后续展示切片 |
| 新建 `adapter-telegram` Maven 模块 | 物理隔离更清晰 | 当前只有一个小型渠道；新增模块需额外批准，先以 Bootstrap Adapter 保持最小变更 |

## 后果

正面结果：

- Kernel/Application 继续无 Telegram、HTTP、Jackson 和 Spring 依赖。
- 网络、响应大小、重试、限流、取消和关闭均由项目 Contract 驱动。
- 默认 CI 完全离线，且不需要 Secret。
- Telegram 与 CLI 复用同一 Message Contract 和权威终态。

代价：

- 项目需要维护最小 Bot API DTO、JSON 校验和 HTTP 错误映射。
- Bot Token 位于 Telegram 规定的 URL Path，必须禁止输出原始 URI。
- 版本 1 没有 Telegram 端实时 Delta、附件或 Markdown 渲染。
- Long Polling offset 只保存在内存，重启可靠性必须由 R6.4 另行解决。

## 重新评估条件

出现以下任一证据时重新评估本 ADR：

- Telegram 能力扩展到大量文件、Inline/Callback、Business 或复杂 Entity，手写协议维护成本显著上升。
- 需要 Webhook 才能满足经批准的部署/延迟目标。
- 第二个以上渠道证明需要独立 Adapter Maven 模块。
- 官方 Bot API 改变 Token/Long Polling/Send Message 协议，当前 Client 无法兼容。
