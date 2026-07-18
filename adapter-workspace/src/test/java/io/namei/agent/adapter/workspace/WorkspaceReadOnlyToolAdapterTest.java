package io.namei.agent.adapter.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceReadOnlyToolAdapterTest {
  @TempDir Path temporaryDirectory;

  @Test
  void readsOnlySafeRelativeUtf8FilesAndProjectsRequestedLineNumbers() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    Path notes = Files.createDirectory(root.resolve("notes"));
    Files.writeString(notes.resolve("today.txt"), "zero\none\ntwo\n");

    ToolResult result =
        new ReadWorkspaceFileTool(root, WorkspaceToolLimits.defaults())
            .execute(Map.of("path", "notes/today.txt", "offset", 1, "limit", 1));

    assertThat(result).isEqualTo(ToolResult.success("2: one"));
  }

  @Test
  void listsOneLevelInStableNameOrderAndOmitsLinks() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    Path directory = Files.createDirectory(root.resolve("directory"));
    Path listing = Files.createDirectory(root.resolve("listing"));
    Files.writeString(listing.resolve("zeta.txt"), "z");
    Files.writeString(listing.resolve("alpha.txt"), "a");
    Files.createSymbolicLink(listing.resolve("outside-link"), directory);

    ToolResult result =
        new ListWorkspaceDirectoryTool(root, WorkspaceToolLimits.defaults())
            .execute(Map.of("path", "listing"));

    assertThat(result).isEqualTo(ToolResult.success("alpha.txt\tFILE\nzeta.txt\tFILE"));
  }

  @Test
  void sortsDirectoryNamesByUnicodeCodePointRatherThanUtf16CodeUnit() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    Path listing = Files.createDirectory(root.resolve("listing"));
    Files.writeString(listing.resolve("\uE000.txt"), "private-use");
    Files.writeString(listing.resolve("😀.txt"), "supplementary");

    ToolResult result =
        new ListWorkspaceDirectoryTool(root, WorkspaceToolLimits.defaults())
            .execute(Map.of("path", "listing"));

    assertThat(result).isEqualTo(ToolResult.success("\uE000.txt\tFILE\n😀.txt\tFILE"));
  }

  @Test
  @Tag("failure")
  void rejectsRootLinksAndPathTraversalWithoutDisclosingPhysicalPaths() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "secret");
    Path rootLink = temporaryDirectory.resolve("root-link");
    Files.createSymbolicLink(rootLink, root);
    Files.createSymbolicLink(root.resolve("linked.txt"), outside.resolve("secret.txt"));

    assertThatThrownBy(
            () -> WorkspaceReadOnlyToolset.enabled(rootLink, WorkspaceToolLimits.defaults()))
        .isInstanceOf(WorkspaceToolContractException.class)
        .extracting(error -> ((WorkspaceToolContractException) error).code())
        .isEqualTo(WorkspaceToolError.WORKSPACE_TOOL_UNAVAILABLE);

    assertSafeError(
        new ReadWorkspaceFileTool(root, WorkspaceToolLimits.defaults())
            .execute(Map.of("path", "linked.txt")),
        WorkspaceToolError.WORKSPACE_PATH_REJECTED,
        root,
        outside);
    assertSafeError(
        new ReadWorkspaceFileTool(root, WorkspaceToolLimits.defaults())
            .execute(Map.of("path", "../outside/secret.txt")),
        WorkspaceToolError.WORKSPACE_PATH_REJECTED,
        root,
        outside);
  }

  @Test
  @Tag("failure")
  void rejectsMissingDirectoriesAndNonTextFilesUsingOnlyStableCodes() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    Files.write(root.resolve("invalid.bin"), new byte[] {(byte) 0xc3, (byte) 0x28});
    var read = new ReadWorkspaceFileTool(root, WorkspaceToolLimits.defaults());
    var list = new ListWorkspaceDirectoryTool(root, WorkspaceToolLimits.defaults());

    assertSafeError(
        read.execute(Map.of("path", "missing.txt")), WorkspaceToolError.WORKSPACE_NOT_FOUND, root);
    assertSafeError(
        read.execute(Map.of("path", "invalid.bin")), WorkspaceToolError.WORKSPACE_NOT_TEXT, root);
    assertSafeError(
        list.execute(Map.of("path", "invalid.bin")),
        WorkspaceToolError.WORKSPACE_PATH_REJECTED,
        root);
  }

  private static void assertSafeError(
      ToolResult result, WorkspaceToolError expected, Path... forbiddenPhysicalPaths) {
    assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
    assertThat(result.content()).isEqualTo(expected.name());
    for (Path forbiddenPhysicalPath : forbiddenPhysicalPaths) {
      assertThat(result.content()).doesNotContain(forbiddenPhysicalPath.toString());
    }
  }
}
