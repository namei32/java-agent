# ADR-0021：将 MCP Assets 保持为目录发现而非 Prompt 注入

- 状态：已接受
- 日期：2026-07-19
- 决策范围：R12-S2 MCP Resources / Prompts

## 背景

Akashic 可发现 MCP Resources 与 Prompts，但 MCP Server 的元数据、正文和参数都是外部不可信输入。把它们直接加入
System Prompt 会形成跨协议提示注入和数据泄漏通道；读取 URI 或取得 Prompt 也可能产生网络、文件或隐式参数访问。

## 决策

R12-S2 只发现并投影受预算的目录元数据。默认关闭；只有既有受控 stdio Runtime 明确进入 `CATALOG_ONLY` 才列出
Resources/Prompts。目录不会改变 Tool Registry、模型 Prompt、Conversation、HTTP 响应或执行权限，且不读取
Resource/Prompt 正文。

## 后果

这不能实现完整 Python MCP 使用体验，但把可审计的身份、排序、预算、Stale 与失败隔离先固定下来。后续读取、Prompt
选择或注入必须单独审批并提供内容消毒、最小参数、审计、取消、回退和本地 Fake Server 验收。
