package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.cutover.CutoverArtifactCategory;
import io.namei.agent.kernel.cutover.CutoverBackupEntry;
import io.namei.agent.kernel.cutover.CutoverBackupManifest;
import io.namei.agent.kernel.cutover.CutoverDifferenceReport;
import io.namei.agent.kernel.cutover.CutoverMode;
import io.namei.agent.kernel.cutover.CutoverState;
import io.namei.agent.kernel.port.CutoverSandboxPort;
import java.util.List;
import org.junit.jupiter.api.Test;

class CutoverRehearsalServiceTest {
  @Test
  void missingOfflineEvidenceCannotBackUpOrAdvanceThePlan() {
    var sandbox = new FakeSandbox();
    var service = new CutoverRehearsalService(sandbox);

    var report = service.rehearse(CutoverMode.REHEARSAL, "a".repeat(64), false);

    assertThat(report.plan().state()).isEqualTo(CutoverState.DRAFT);
    assertThat(sandbox.backedUp).isFalse();
  }

  @Test
  void verifiedDifferenceFreeSandboxReachesReadyButNeverProductionStates() {
    var sandbox = new FakeSandbox();
    var service = new CutoverRehearsalService(sandbox);

    var report = service.rehearse(CutoverMode.REHEARSAL, "a".repeat(64), true);

    assertThat(report.plan().state()).isEqualTo(CutoverState.READY);
    assertThat(report.manifest()).isPresent();
    assertThat(report.difference())
        .hasValueSatisfying(value -> assertThat(value.withinThreshold()).isTrue());
  }

  private static final class FakeSandbox implements CutoverSandboxPort {
    private final CutoverBackupManifest manifest =
        new CutoverBackupManifest(
            "backup-fixture",
            List.of(
                new CutoverBackupEntry(
                    CutoverArtifactCategory.CONFIG, "config/config.toml", "b".repeat(64), 1)));
    private boolean backedUp;

    @Override
    public CutoverBackupManifest backup() {
      backedUp = true;
      return manifest;
    }

    @Override
    public boolean verify(CutoverBackupManifest expected) {
      return expected.equals(manifest);
    }

    @Override
    public CutoverDifferenceReport compare(CutoverBackupManifest expected, int threshold) {
      return new CutoverDifferenceReport(1, 0, threshold);
    }
  }
}
