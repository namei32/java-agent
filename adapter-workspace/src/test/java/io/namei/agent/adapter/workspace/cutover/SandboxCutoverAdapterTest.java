package io.namei.agent.adapter.workspace.cutover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.cutover.CutoverContractViolation;
import io.namei.agent.kernel.cutover.CutoverStableCode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxCutoverAdapterTest {
  @TempDir Path tempDir;

  @Test
  void copiesOnlyAllowlistedSandboxArtifactsCreatesManifestAndDetectsDifferences()
      throws Exception {
    var sandbox =
        SandboxCutoverAdapter.createNew(
            sandbox(), Path.of("/repository"), Path.of("/workspace"), Path.of("/home"));
    Path config = sandbox.root().resolve("input/config/config.toml");
    Path database = sandbox.root().resolve("input/sqlite/sessions.db");
    Files.createDirectories(config.getParent());
    Files.createDirectories(database.getParent());
    Files.writeString(config, "provider = 'redacted'");
    Files.writeString(database, "not-a-real-sqlite-fixture");

    var manifest = sandbox.backup();

    assertThat(manifest.entries()).hasSize(2);
    assertThat(sandbox.verify(manifest)).isTrue();
    assertThat(Files.readString(config)).isEqualTo("provider = 'redacted'");
    assertThat(
            Files.isRegularFile(
                sandbox
                    .root()
                    .resolve("backups")
                    .resolve(manifest.backupId())
                    .resolve("manifest-v1.txt")))
        .isTrue();

    Files.writeString(config, "provider = 'changed'");
    var difference = sandbox.compare(manifest, 0);
    assertThat(difference.changedEntries()).isOne();
    assertThat(difference.withinThreshold()).isFalse();
    assertThatThrownBy(difference::requireWithinThreshold)
        .isInstanceOf(CutoverContractViolation.class)
        .extracting(error -> ((CutoverContractViolation) error).code())
        .isEqualTo(CutoverStableCode.DIFFERENCE_THRESHOLD_EXCEEDED);
  }

  @Test
  void refusesWorkspaceOrUnexpectedInputFiles() throws Exception {
    assertThatThrownBy(
            () ->
                SandboxCutoverAdapter.createNew(
                    tempDir.resolve("workspace"),
                    Path.of("/repository"),
                    tempDir.resolve("workspace"),
                    Path.of("/home")))
        .isInstanceOf(SandboxCutoverException.class)
        .extracting(error -> ((SandboxCutoverException) error).code())
        .isEqualTo(CutoverStableCode.SANDBOX_UNSAFE);

    Path unplanned = tempDir.resolve("unplanned");
    Files.createDirectories(unplanned.resolve("input"));
    assertThatThrownBy(
            () ->
                SandboxCutoverAdapter.openExisting(
                    unplanned, Path.of("/repository"), Path.of("/workspace"), Path.of("/home")))
        .isInstanceOf(SandboxCutoverException.class)
        .extracting(error -> ((SandboxCutoverException) error).code())
        .isEqualTo(CutoverStableCode.SANDBOX_UNSAFE);

    var sandbox =
        SandboxCutoverAdapter.createNew(
            sandbox(), Path.of("/repository"), Path.of("/workspace"), Path.of("/home"));
    Path unexpected = sandbox.root().resolve("input/secret.txt");
    Files.createDirectories(unexpected.getParent());
    Files.writeString(unexpected, "not allowed");

    assertThatThrownBy(sandbox::backup)
        .isInstanceOf(SandboxCutoverException.class)
        .extracting(error -> ((SandboxCutoverException) error).code())
        .isEqualTo(CutoverStableCode.SANDBOX_UNSAFE);
  }

  private Path sandbox() {
    return tempDir.resolve("sandbox");
  }
}
