package io.namei.agent.kernel.cutover;

import java.util.Objects;
import java.util.regex.Pattern;

public record CutoverBackupEntry(
    CutoverArtifactCategory category, String relativePath, String sha256, long bytes) {
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public CutoverBackupEntry {
    category = Objects.requireNonNull(category, "category");
    if (relativePath == null
        || relativePath.isBlank()
        || relativePath.startsWith("/")
        || relativePath.contains("\\")
        || relativePath.contains("..")) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_CONTRACT_INVALID);
    }
    if (sha256 == null || !HASH.matcher(sha256).matches() || bytes < 0) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_CONTRACT_INVALID);
    }
  }
}
