# ADR-0022：以独立显式根目录提供只读 Workspace Tools

- 状态：已接受
- 日期：2026-07-19
- 决策范围：R11-B3 `read_file` / `list_dir`

## 背景

Python Filesystem Tool 允许由配置或进程目录决定路径，并同时混合读、写、编辑、图片和宽松编码。Java 的
`${agent.workspace}` 已保存 SQLite/运行时资产；默认向模型开放它会让只读 Tool 读取不属于用户选择的内部数据，
也为后续写入误用埋下边界混淆。

## 决策

Java 只读 Tool 使用独立 `agent.workspace-tools.root`，默认 `DISABLED`，绝不回退到 `${agent.workspace}` 或当前
工作目录。只接受相对路径、拒绝每一级链接与越界，严格 UTF-8 和固定输出预算。两个 Tool 作为 Deferred Builtin
注册，只有显式搜索后才向模型发送 Schema。

## 后果

这不能兼容 Python 的任意路径、图片和宽松解码，却形成一个可审计的最小读取能力。未来写入、编辑、递归、图片或
真实 Workspace 演练需各自重新批准，不能把本 Root 或 `READ_ONLY` 风险继承为写入权限。
