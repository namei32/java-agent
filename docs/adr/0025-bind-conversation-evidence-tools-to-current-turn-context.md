# ADR-0025：以 Turn 上下文受限方式暴露当前会话证据 Tool

- 状态：已接受并实施；阶段门禁已通过
- 日期：2026-07-19
- 决策范围：R11-B4 当前会话只读证据 Tool

## 背景

Python 的消息查找 Tool 直接接受/回显 Session Key 和原始消息 ID。Java `Tool` 是静态、无参数 Context 的 Port，
而 `SessionRepository.load` 也刻意不向普通调用者暴露持久 ID。把 Session ID 加入 Tool JSON，或把它存进全局
`ThreadLocal`，会造成跨会话读取、并发串扰或 Plugin/MCP 泄漏。

## 决策

保留 Kernel `Tool` 的无上下文默认接口，并仅在 Application Layer 引入 `ContextualTool` 与不可公开的
`ToolInvocationContext`。`ToolLoop` 在当前 Turn 内显式传递 Context；只有 `ConversationEvidenceTool` 获得一个
绑定当前 Session 的只读 Scope。模型只看到 `msg-v1:<seq>` 引用，不能提交或接收原始 Session Key。

## 后果

现有 Tool、MCP、Workspace Adapter 和 Plugin Wire 不需要看到 Session Context；虚拟线程与取消路径也不依赖
ThreadLocal。Python 的跨 Session/原始 ID 表面不迁移，作为安全替代明确记录。未来任何新的 ContextualTool 必须单独
声明所需 Scope、可见字段、取消和审计边界，不能把原始 Context 扩展成通用数据袋。
