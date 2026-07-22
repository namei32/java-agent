package io.namei.agent.adapter.mcp;

import java.util.Objects;

/** 不包含 Server 名称、命令、路径或错误详情的聚合运行状态。 */
public record McpRuntimeStatus(
    State state,
    int configuredServers,
    int readyServers,
    int staleServers,
    int unavailableServers) {

  public McpRuntimeStatus {
    Objects.requireNonNull(state, "state");
    if (configuredServers < 0 || readyServers < 0 || staleServers < 0 || unavailableServers < 0) {
      throw new IllegalArgumentException("MCP 状态计数不能为负数");
    }
    if (readyServers + staleServers + unavailableServers > configuredServers) {
      throw new IllegalArgumentException("MCP Server 状态计数超过配置总数");
    }
  }

  public static McpRuntimeStatus disabled() {
    return new McpRuntimeStatus(State.DISABLED, 0, 0, 0, 0);
  }

  public enum State {
    DISABLED,
    STARTING,
    READY,
    DEGRADED,
    CLOSING,
    CLOSED
  }
}
