package io.namei.agent.adapter.workspace;

import io.namei.agent.kernel.port.Tool;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** The explicitly enabled or empty workspace tool set. Disabled construction performs no I/O. */
public final class WorkspaceReadOnlyToolset {
  private static final WorkspaceReadOnlyToolset DISABLED = new WorkspaceReadOnlyToolset(List.of());

  private final List<Tool> tools;

  private WorkspaceReadOnlyToolset(List<Tool> tools) {
    this.tools = List.copyOf(tools);
  }

  public static WorkspaceReadOnlyToolset disabled() {
    return DISABLED;
  }

  public static WorkspaceReadOnlyToolset enabled(Path root, WorkspaceToolLimits limits) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(limits, "limits");
    WorkspacePathResolver.open(root);
    return new WorkspaceReadOnlyToolset(
        List.of(
            new ReadWorkspaceFileTool(root, limits), new ListWorkspaceDirectoryTool(root, limits)));
  }

  public List<Tool> tools() {
    return tools;
  }
}
