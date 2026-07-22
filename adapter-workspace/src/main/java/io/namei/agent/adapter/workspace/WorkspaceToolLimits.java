package io.namei.agent.adapter.workspace;

/** 只读 Workspace 投影使用的不可变输出与源数据预算。 */
public record WorkspaceToolLimits(
    int maxSourceBytes,
    int maxLines,
    int maxOutputBytes,
    int maxOutputCodePoints,
    int maxDirectoryEntries) {
  public static final int MAX_SOURCE_BYTES = 1_000_000;
  public static final int MAX_LINES = 400;
  public static final int MAX_OUTPUT_BYTES = 10_000;
  public static final int MAX_OUTPUT_CODE_POINTS = 20_000;
  public static final int MAX_DIRECTORY_ENTRIES = 256;
  static final String TRUNCATION_MARKER = "[TRUNCATED]";
  private static final int TRUNCATION_MARKER_BYTES = TRUNCATION_MARKER.length();
  private static final int TRUNCATION_MARKER_CODE_POINTS =
      TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length());
  private static final WorkspaceToolLimits DEFAULTS =
      new WorkspaceToolLimits(
          MAX_SOURCE_BYTES,
          MAX_LINES,
          MAX_OUTPUT_BYTES,
          MAX_OUTPUT_CODE_POINTS,
          MAX_DIRECTORY_ENTRIES);

  public WorkspaceToolLimits {
    if (maxSourceBytes <= 0
        || maxSourceBytes > MAX_SOURCE_BYTES
        || maxLines <= 0
        || maxLines > MAX_LINES
        || maxOutputBytes < TRUNCATION_MARKER_BYTES
        || maxOutputBytes > MAX_OUTPUT_BYTES
        || maxOutputCodePoints < TRUNCATION_MARKER_CODE_POINTS
        || maxOutputCodePoints > MAX_OUTPUT_CODE_POINTS
        || maxDirectoryEntries <= 0) {
      throw WorkspaceToolError.WORKSPACE_TOOL_UNAVAILABLE.violation();
    }
    if (maxDirectoryEntries > MAX_DIRECTORY_ENTRIES) {
      throw WorkspaceToolError.WORKSPACE_TOOL_UNAVAILABLE.violation();
    }
  }

  public static WorkspaceToolLimits defaults() {
    return DEFAULTS;
  }
}
