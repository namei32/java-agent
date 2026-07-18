package io.namei.agent.adapter.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceReadOnlyToolBudgetTest {
  @TempDir Path temporaryDirectory;

  @Test
  void projectsBoundedLinesWithAnExplicitTruncationMarker() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    Files.writeString(root.resolve("notes.txt"), "zero\none\ntwo");
    WorkspaceToolLimits limits = new WorkspaceToolLimits(100, 2, 20, 20, 2);

    ToolResult result =
        new ReadWorkspaceFileTool(root, limits).execute(Map.of("path", "notes.txt", "limit", 3));

    assertThat(result).isEqualTo(ToolResult.success("1: zero\n[TRUNCATED]"));
    assertBounded(result.content(), limits);
  }

  @Test
  void boundsUnicodeProjectionByCodePointsAsWellAsUtf8Bytes() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    Files.writeString(root.resolve("unicode.txt"), "甲甲甲甲甲甲甲甲甲甲");
    WorkspaceToolLimits limits = new WorkspaceToolLimits(100, 2, 100, 12, 2);

    ToolResult result =
        new ReadWorkspaceFileTool(root, limits).execute(Map.of("path", "unicode.txt", "limit", 1));

    assertThat(result).isEqualTo(ToolResult.success("[TRUNCATED]"));
    assertBounded(result.content(), limits);
  }

  @Test
  @Tag("failure")
  void rejectsSourceFilesAndDirectoryProjectionsThatExceedFixedBudgets() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    Files.writeString(root.resolve("large.txt"), "12345");
    Path directory = Files.createDirectory(root.resolve("many"));
    Files.writeString(directory.resolve("a.txt"), "a");
    Files.writeString(directory.resolve("b.txt"), "b");
    Files.writeString(directory.resolve("c.txt"), "c");
    WorkspaceToolLimits limits = new WorkspaceToolLimits(4, 2, 100, 100, 2);

    assertError(
        new ReadWorkspaceFileTool(root, limits).execute(Map.of("path", "large.txt")),
        WorkspaceToolError.WORKSPACE_BUDGET_EXCEEDED);
    assertError(
        new ListWorkspaceDirectoryTool(root, limits).execute(Map.of("path", "many")),
        WorkspaceToolError.WORKSPACE_BUDGET_EXCEEDED);
  }

  private static void assertBounded(String value, WorkspaceToolLimits limits) {
    assertThat(value.getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(limits.maxOutputBytes());
    assertThat(value.codePointCount(0, value.length()))
        .isLessThanOrEqualTo(limits.maxOutputCodePoints());
  }

  private static void assertError(ToolResult result, WorkspaceToolError error) {
    assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
    assertThat(result.content()).isEqualTo(error.name());
  }
}
