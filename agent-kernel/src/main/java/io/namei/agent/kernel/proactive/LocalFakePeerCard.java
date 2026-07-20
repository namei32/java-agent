package io.namei.agent.kernel.proactive;

import java.util.Objects;

/** Static Card projection for the fixed local fake peer; it deliberately cannot carry task text. */
public record LocalFakePeerCard(LocalFakePeerManifest manifest, LocalFakePeerTaskKind taskKind) {
  public LocalFakePeerCard {
    manifest = Objects.requireNonNull(manifest, "manifest");
    taskKind = Objects.requireNonNull(taskKind, "taskKind");
    if (!manifest.equals(LocalFakePeerManifest.approved())
        || taskKind != LocalFakePeerTaskKind.LOCAL_FAKE_TASK) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }

  public static LocalFakePeerCard approved() {
    return new LocalFakePeerCard(
        LocalFakePeerManifest.approved(), LocalFakePeerTaskKind.LOCAL_FAKE_TASK);
  }

  @Override
  public String toString() {
    return "LocalFakePeerCard[manifest=<redacted>, taskKind=" + taskKind + "]";
  }
}
