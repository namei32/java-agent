package io.namei.agent.adapter.mcp;

/** MCP Adapter 支持的启动模式；当前仅允许完全禁用或静态只读。 */
public enum McpMode {
  /** 不读取配置、不启动子进程，也不注册 MCP 工具。 */
  DISABLED,
  /** 从固定配置启动 stdio Server，并只发布声明为只读的工具。 */
  STATIC_READ_ONLY
}
