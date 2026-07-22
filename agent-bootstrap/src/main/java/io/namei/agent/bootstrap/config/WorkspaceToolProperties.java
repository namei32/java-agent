package io.namei.agent.bootstrap.config;

import io.namei.agent.adapter.workspace.WorkspaceToolLimits;
import io.namei.agent.adapter.workspace.WorkspaceToolMode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Java 自持有界 Workspace 读取 Tool 的默认禁用配置。 */
@ConfigurationProperties("agent.workspace-tools")
public final class WorkspaceToolProperties {
  private final WorkspaceToolMode mode;
  private final String root;
  private final WorkspaceToolLimits limits;

  @ConstructorBinding
  public WorkspaceToolProperties(
      @DefaultValue("DISABLED") String mode,
      @DefaultValue("") String root,
      @DefaultValue("1000000") int maxSourceBytes,
      @DefaultValue("400") int maxLines,
      @DefaultValue("10000") int maxOutputBytes,
      @DefaultValue("20000") int maxOutputCodePoints,
      @DefaultValue("256") int maxDirectoryEntries) {
    this.mode = WorkspaceToolMode.parse(mode);
    this.root = root == null ? "" : root;
    this.limits =
        new WorkspaceToolLimits(
            maxSourceBytes, maxLines, maxOutputBytes, maxOutputCodePoints, maxDirectoryEntries);
  }

  public WorkspaceToolMode mode() {
    return mode;
  }

  /** 仅在调用方显式选择 READ_ONLY 模式后解析 Root。 */
  public Path root() {
    if (root.isBlank()) {
      throw new IllegalArgumentException("agent.workspace-tools.root 必填");
    }
    try {
      Path parsed = Path.of(root);
      if (!parsed.isAbsolute()) {
        throw new IllegalArgumentException("agent.workspace-tools.root 必须是绝对路径");
      }
      return parsed.normalize();
    } catch (InvalidPathException invalid) {
      throw new IllegalArgumentException("agent.workspace-tools.root 无效", invalid);
    }
  }

  public WorkspaceToolLimits limits() {
    return limits;
  }

  @Override
  public String toString() {
    return "WorkspaceToolProperties[mode=" + mode + ", root=<configured>, budgets=<configured>]";
  }
}
