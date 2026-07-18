package io.namei.agent.adapter.workspace;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Contract definition for the one-level, read-only workspace directory projection. */
public final class ListWorkspaceDirectoryTool implements Tool {
  private static final ToolDefinition DEFINITION =
      new ToolDefinition(
          "list_dir",
          "列出显式 Workspace Root 内一层目录的安全条目。",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of("path", Map.of("type", "string")),
              "required",
              List.of("path"),
              "additionalProperties",
              false),
          ToolRisk.READ_ONLY,
          "workspace-read-only-v1");

  private final Path root;
  private final WorkspaceToolLimits limits;

  public ListWorkspaceDirectoryTool(Path root, WorkspaceToolLimits limits) {
    this.root = Objects.requireNonNull(root, "root");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  @Override
  public ToolDefinition definition() {
    return DEFINITION;
  }

  @Override
  public ToolResult execute(Map<String, Object> arguments) {
    try {
      WorkspaceToolPath path = WorkspaceToolArguments.requiredPath(arguments, Set.of("path"));
      WorkspacePathResolver.ResolvedPath resolved = WorkspacePathResolver.open(root).resolve(path);
      if (!resolved.initialAttributes().isDirectory()) {
        return error(WorkspaceToolError.WORKSPACE_PATH_REJECTED);
      }
      List<Entry> entries = entries(resolved, limits.maxDirectoryEntries());
      resolved.verifyUnchanged();
      return ToolResult.success(
          entries.stream()
              .map(entry -> entry.name() + "\t" + entry.type())
              .reduce((a, b) -> a + "\n" + b)
              .orElse("目录为空。"));
    } catch (WorkspaceToolContractException violation) {
      return error(violation.code());
    } catch (IOException | RuntimeException failure) {
      return error(WorkspaceToolError.WORKSPACE_PATH_REJECTED);
    }
  }

  private static List<Entry> entries(WorkspacePathResolver.ResolvedPath directory, int maximum)
      throws IOException {
    var entries = new ArrayList<Entry>();
    int inspected = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory.path())) {
      for (Path candidate : stream) {
        if (++inspected > maximum * 4) {
          throw WorkspaceToolError.WORKSPACE_BUDGET_EXCEEDED.violation();
        }
        String name = candidate.getFileName().toString();
        if (name.isBlank() || name.codePoints().anyMatch(Character::isISOControl)) {
          continue;
        }
        BasicFileAttributes attributes =
            Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
          continue;
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(directory.path()) || !real.equals(candidate)) {
          continue;
        }
        String type =
            attributes.isRegularFile() ? "FILE" : attributes.isDirectory() ? "DIRECTORY" : null;
        if (type == null) {
          continue;
        }
        entries.add(new Entry(name, type));
        if (entries.size() > maximum) {
          throw WorkspaceToolError.WORKSPACE_BUDGET_EXCEEDED.violation();
        }
      }
    }
    entries.sort(Comparator.comparing(Entry::name, ListWorkspaceDirectoryTool::compareCodePoints));
    return List.copyOf(entries);
  }

  private static int compareCodePoints(String left, String right) {
    int leftOffset = 0;
    int rightOffset = 0;
    while (leftOffset < left.length() && rightOffset < right.length()) {
      int leftPoint = left.codePointAt(leftOffset);
      int rightPoint = right.codePointAt(rightOffset);
      int comparison = Integer.compare(leftPoint, rightPoint);
      if (comparison != 0) {
        return comparison;
      }
      leftOffset += Character.charCount(leftPoint);
      rightOffset += Character.charCount(rightPoint);
    }
    return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
  }

  private static ToolResult error(WorkspaceToolError error) {
    return ToolResult.error(error.name());
  }

  private record Entry(String name, String type) {}
}
