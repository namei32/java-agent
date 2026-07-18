package io.namei.agent.adapter.workspace;

/** Stable, sanitized error codes exposed by the read-only workspace tools. */
public enum WorkspaceToolError {
  WORKSPACE_TOOL_UNAVAILABLE,
  WORKSPACE_PATH_REJECTED,
  WORKSPACE_NOT_FOUND,
  WORKSPACE_NOT_TEXT,
  WORKSPACE_BUDGET_EXCEEDED;

  public static WorkspaceToolError parse(String value) {
    if (value == null) {
      throw unavailable();
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw unavailable();
    }
  }

  WorkspaceToolContractException violation() {
    return new WorkspaceToolContractException(this);
  }

  private static WorkspaceToolContractException unavailable() {
    return WORKSPACE_TOOL_UNAVAILABLE.violation();
  }
}
