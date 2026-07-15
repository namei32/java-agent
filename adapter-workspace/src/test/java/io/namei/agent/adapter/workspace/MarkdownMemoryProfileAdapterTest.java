package io.namei.agent.adapter.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.memory.MemoryProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownMemoryProfileAdapterTest {
  @TempDir Path temporaryDirectory;

  @Test
  void missingWorkspaceAndFilesProduceEmptyProfileWithoutWriting() {
    Path workspace = temporaryDirectory.resolve("missing-workspace");
    var adapter = new MarkdownMemoryProfileAdapter(workspace, 65_536);

    assertThat(adapter.load()).isEqualTo(MemoryProfile.empty());
    assertThat(workspace).doesNotExist();
  }

  @Test
  void readsFixedUtf8FilesAndRemovesRecentTurnsFromRecentContext() throws Exception {
    Path workspace = temporaryDirectory.resolve("workspace");
    Path memory = Files.createDirectories(workspace.resolve("memory"));
    Files.writeString(memory.resolve("SELF.md"), " 自我认知 \n");
    Files.writeString(memory.resolve("MEMORY.md"), "# 长期记忆\n- 偏好中文\n");
    Files.writeString(
        memory.resolve("RECENT_CONTEXT.md"),
        "# Recent Context\n\n## Compression\n- R4\n\n## Recent Turns\n[user] 重复窗口");
    var adapter = new MarkdownMemoryProfileAdapter(workspace, 65_536);

    assertThat(adapter.load())
        .isEqualTo(
            new MemoryProfile(
                " 自我认知 \n", "# 长期记忆\n- 偏好中文\n", "# Recent Context\n\n## Compression\n- R4"));
    try (var files = Files.list(memory)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .containsExactlyInAnyOrder("SELF.md", "MEMORY.md", "RECENT_CONTEXT.md");
    }
  }

  @Test
  @Tag("failure")
  void rejectsNonRegularInvalidUtf8AndOversizedFiles() throws Exception {
    Path nonRegularWorkspace = temporaryDirectory.resolve("non-regular");
    Files.createDirectories(nonRegularWorkspace.resolve("memory/SELF.md"));
    assertUnavailable(new MarkdownMemoryProfileAdapter(nonRegularWorkspace, 65_536));

    Path invalidWorkspace = temporaryDirectory.resolve("invalid-utf8");
    Path invalidMemory = Files.createDirectories(invalidWorkspace.resolve("memory"));
    Files.write(invalidMemory.resolve("MEMORY.md"), new byte[] {(byte) 0xc3, (byte) 0x28});
    assertUnavailable(new MarkdownMemoryProfileAdapter(invalidWorkspace, 65_536));

    Path largeWorkspace = temporaryDirectory.resolve("oversized");
    Path largeMemory = Files.createDirectories(largeWorkspace.resolve("memory"));
    Files.writeString(largeMemory.resolve("RECENT_CONTEXT.md"), "12345");
    assertUnavailable(new MarkdownMemoryProfileAdapter(largeWorkspace, 4));
  }

  @Test
  @Tag("failure")
  void rejectsSymbolicLinkThatEscapesWorkspace() throws Exception {
    Path workspace = temporaryDirectory.resolve("workspace-link");
    Path memory = Files.createDirectories(workspace.resolve("memory"));
    Path outside = temporaryDirectory.resolve("outside.md");
    Files.writeString(outside, "外部秘密");
    Files.createSymbolicLink(memory.resolve("MEMORY.md"), outside);

    assertUnavailable(new MarkdownMemoryProfileAdapter(workspace, 65_536));
  }

  private static void assertUnavailable(MarkdownMemoryProfileAdapter adapter) {
    assertThatThrownBy(adapter::load)
        .isInstanceOf(MemoryProfileAccessException.class)
        .hasMessage("记忆 Profile 当前不可用");
  }
}
