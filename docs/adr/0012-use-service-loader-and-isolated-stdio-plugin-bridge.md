# ADR-0012：使用 ServiceLoader 与隔离 stdio Plugin Bridge

- 状态：已接受
- 日期：2026-07-18
- 阶段：R7

## 背景

Python `agent/plugins/` 通过动态 import、共享上下文和装饰器注册 Hook/Tool/Channel。直接在 JVM 内加载
Python 或把可变 Agent 对象交给扩展，会绕过 Java 的 Tool、审批、取消、配置和安全边界。

## 决策

受信 Java 扩展使用 JDK `ServiceLoader`。非 Java 扩展只可经过项目拥有的、单进程、stdio JSON Bridge，且
只获得安全事件投影。两类扩展均默认关闭、静态配置、不可热加载；V1 只允许观察型 Tap。

## 后果

获得稳定 JVM ABI、明确关闭与失败隔离，不需新增框架或 Maven 模块。代价是 Python 动态插件不能原样运行，
并且 V1 不提供任意 Tool/Channel/可变 Hook；这些需要后续单独 Capability Contract。
