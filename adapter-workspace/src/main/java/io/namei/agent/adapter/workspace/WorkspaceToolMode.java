package io.namei.agent.adapter.workspace;

/** Workspace Tool 的显式选择加入模式；解析有意区分大小写。 */
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
