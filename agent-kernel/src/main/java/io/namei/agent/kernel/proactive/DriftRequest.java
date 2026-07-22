package io.namei.agent.kernel.proactive;

import java.util.Objects;
import java.util.regex.Pattern;

/** 只读 Drift 输入：包含 Parent 和已安全化的目标引用，绝不包含 Workspace 路径。 */
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
