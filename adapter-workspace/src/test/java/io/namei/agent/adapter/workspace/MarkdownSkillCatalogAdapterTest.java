package io.namei.agent.adapter.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.skill.SkillCatalogSnapshot;
import io.namei.agent.kernel.skill.SkillSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownSkillCatalogAdapterTest {
  @TempDir Path temporaryDirectory;

  @Test
  void missingRootsProduceAnEmptySnapshotWithoutWriting() {
    Path builtin = temporaryDirectory.resolve("missing-builtin");
    Path workspace = temporaryDirectory.resolve("missing-workspace");
    var adapter = adapter(builtin, workspace, requirements(Map.of(), Map.of()), 8, 8_192);

    assertThat(adapter.snapshot()).isEqualTo(SkillCatalogSnapshot.empty());
    assertThat(builtin).doesNotExist();
    assertThat(workspace).doesNotExist();
  }

  @Test
  void workspaceOverridesBuiltinAndOnlyAvailableAlwaysSkillProvidesBody() throws Exception {
    Path builtin = temporaryDirectory.resolve("builtin");
    Path workspace = temporaryDirectory.resolve("workspace");
    skill(builtin, "alpha", "Alpha builtin", "{\"akashic\":{}}", "Alpha body");
    skill(
        builtin,
        "calendar",
        "Builtin calendar",
        "{\"akashic\":{\"always\":true,\"requires\":{\"bins\":[\"cal\"]}}}",
        "Builtin calendar body");
    skill(
        workspace,
        "calendar",
        "Workspace calendar",
        "{\"skill\":{\"always\":true,\"requires\":{\"env\":[\"CALENDAR_TOKEN\"]}}}",
        "Workspace calendar body");
    skill(
        workspace,
        "daily-rules",
        "Daily rules",
        "{\"akashic\":{\"always\":true,\"requires\":{\"bins\":[\"date\"]}}}",
        "Use Chinese.\n");

    var adapter =
        adapter(
            builtin,
            workspace,
            requirements(Map.of("cal", true, "date", true), Map.of("CALENDAR_TOKEN", false)),
            8,
            8_192);

    SkillCatalogSnapshot snapshot = adapter.snapshot();

    assertThat(snapshot.descriptors())
        .extracting(
            descriptor -> descriptor.name(),
            descriptor -> descriptor.source(),
            descriptor -> descriptor.available())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("alpha", SkillSource.BUILTIN, true),
            org.assertj.core.groups.Tuple.tuple("calendar", SkillSource.WORKSPACE, false),
            org.assertj.core.groups.Tuple.tuple("daily-rules", SkillSource.WORKSPACE, true));
    assertThat(snapshot.descriptors().get(1).description()).isEqualTo("Workspace calendar");
    assertThat(snapshot.activeContents())
        .extracting(content -> content.name(), content -> content.body())
        .containsExactly(org.assertj.core.groups.Tuple.tuple("daily-rules", "Use Chinese."));
    assertThat(adapter.readAvailable("alpha"))
        .hasValueSatisfying(content -> assertThat(content.body()).isEqualTo("Alpha body"));
    assertThat(adapter.readAvailable("calendar")).isEmpty();
    assertThat(adapter.readAvailable("../calendar")).isEmpty();
  }

  @Test
  void readsTheAvailableWorkspaceOverrideWithoutExposingFrontmatterOrKeepingStaleContent()
      throws Exception {
    Path builtin = temporaryDirectory.resolve("builtin");
    Path workspace = temporaryDirectory.resolve("workspace");
    skill(builtin, "daily-rules", "Builtin rules", "{\"akashic\":{}}", "Builtin body");
    skill(workspace, "daily-rules", "Workspace rules", "{\"akashic\":{}}", "Workspace body");
    Path workspaceSkill = workspace.resolve("daily-rules/SKILL.md");
    var adapter = adapter(builtin, workspace, requirements(Map.of(), Map.of()), 8, 8_192);

    assertThat(adapter.readAvailable("daily-rules"))
        .hasValueSatisfying(
            content -> {
              assertThat(content.body()).isEqualTo("Workspace body");
              assertThat(content.body()).doesNotContain("metadata:", "---", "Workspace rules");
            });

    Files.write(workspaceSkill, new byte[] {(byte) 0xc3, (byte) 0x28});

    assertThat(adapter.readAvailable("daily-rules"))
        .hasValueSatisfying(content -> assertThat(content.body()).isEqualTo("Builtin body"));
  }

  @Test
  @Tag("failure")
  void ignoresInvalidMetadataOversizedAndInvalidUtf8CandidatesWithoutWriting() throws Exception {
    Path builtin = temporaryDirectory.resolve("builtin");
    Path workspace = temporaryDirectory.resolve("workspace");
    skill(builtin, "valid", "Valid skill", "{\"akashic\":{}}", "valid");
    skill(builtin, "mismatch", "Mismatch", "{\"akashic\":{}}", "mismatch", "other-name");
    skill(builtin, "bad-meta", "Bad metadata", "not-json", "body");
    skill(builtin, "oversized", "Oversized", "{\"akashic\":{}}", "x".repeat(2_000));
    Path invalid = Files.createDirectories(workspace.resolve("invalid-utf8"));
    Files.write(invalid.resolve("SKILL.md"), new byte[] {(byte) 0xc3, (byte) 0x28});

    var adapter = adapter(builtin, workspace, requirements(Map.of(), Map.of()), 8, 512);

    assertThat(adapter.snapshot().descriptors())
        .extracting(descriptor -> descriptor.name())
        .containsExactly("valid");
    assertThat(Files.exists(workspace.resolve("new-file"))).isFalse();
  }

  @Test
  @Tag("failure")
  void ignoresSymbolicLinksThatEscapeAConfiguredRoot() throws Exception {
    Path builtin = temporaryDirectory.resolve("builtin");
    Path workspace = temporaryDirectory.resolve("workspace");
    Path outside = temporaryDirectory.resolve("outside");
    skill(outside, "escaped", "Escaped", "{\"akashic\":{}}", "outside");
    Files.createDirectories(builtin);
    Files.createSymbolicLink(builtin.resolve("escaped"), outside.resolve("escaped"));
    skill(workspace, "safe", "Safe", "{\"akashic\":{}}", "safe");

    var adapter = adapter(builtin, workspace, requirements(Map.of(), Map.of()), 8, 8_192);

    assertThat(adapter.snapshot().descriptors())
        .extracting(descriptor -> descriptor.name())
        .containsExactly("safe");
  }

  private static MarkdownSkillCatalogAdapter adapter(
      Path builtin,
      Path workspace,
      SkillRequirementChecker requirements,
      int maxSkills,
      int maxFileBytes) {
    return new MarkdownSkillCatalogAdapter(
        builtin, workspace, requirements, new SkillCatalogLimits(maxSkills, maxFileBytes));
  }

  private static SkillRequirementChecker requirements(
      Map<String, Boolean> binaries, Map<String, Boolean> environment) {
    return new SkillRequirementChecker() {
      @Override
      public boolean binaryAvailable(String name) {
        return binaries.getOrDefault(name, false);
      }

      @Override
      public boolean environmentAvailable(String name) {
        return environment.getOrDefault(name, false);
      }
    };
  }

  private static void skill(
      Path root, String directory, String description, String metadata, String body)
      throws Exception {
    skill(root, directory, description, metadata, body, directory);
  }

  private static void skill(
      Path root,
      String directory,
      String description,
      String metadata,
      String body,
      String frontmatterName)
      throws Exception {
    Path skill = Files.createDirectories(root.resolve(directory));
    Files.writeString(
        skill.resolve("SKILL.md"),
        "---\n"
            + "name: "
            + frontmatterName
            + "\n"
            + "description: "
            + description
            + "\n"
            + "metadata: "
            + metadata
            + "\n"
            + "---\n\n"
            + body,
        StandardCharsets.UTF_8);
  }
}
