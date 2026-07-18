package io.namei.agent.adapter.workspace;

/** Explicit opt-in mode for workspace tools; parsing is deliberately case-sensitive. */
public enum WorkspaceToolMode {
  DISABLED,
  READ_ONLY;

  public static WorkspaceToolMode parse(String value) {
    if (value == null) {
      throw WorkspaceToolError.WORKSPACE_TOOL_UNAVAILABLE.violation();
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw WorkspaceToolError.WORKSPACE_TOOL_UNAVAILABLE.violation();
    }
  }
}
