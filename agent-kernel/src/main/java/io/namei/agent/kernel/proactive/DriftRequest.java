package io.namei.agent.kernel.proactive;

import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only drift input: parent and an already-safe target reference, never a workspace path. */
public record DriftRequest(
    ProactiveJobRef parentJobRef, String targetHash, int maxSummaryCharacters) {
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public DriftRequest {
    parentJobRef = Objects.requireNonNull(parentJobRef, "parentJobRef");
    if (targetHash == null || !HASH.matcher(targetHash).matches()) {
      throw ProactiveContract.violation(ProactiveStableCode.DRIFT_READ_ONLY);
    }
    if (maxSummaryCharacters < 1 || maxSummaryCharacters > 4_000) {
      throw ProactiveContract.violation(ProactiveStableCode.SUBAGENT_BUDGET_EXHAUSTED);
    }
  }
}
