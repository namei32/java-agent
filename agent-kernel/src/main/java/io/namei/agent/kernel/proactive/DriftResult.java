package io.namei.agent.kernel.proactive;

import java.util.Objects;
import java.util.Optional;

/** 只有有界安全投影可以离开只读诊断边界。 */
public record DriftResult(Status status, Optional<String> safeSummary) {
  public enum Status {
    CLEAN,
    DRIFT_DETECTED,
    CANCELLED
  }

  public DriftResult {
    status = Objects.requireNonNull(status, "status");
    safeSummary = Objects.requireNonNull(safeSummary, "safeSummary");
    if ((status == Status.DRIFT_DETECTED && safeSummary.isEmpty())
        || (status != Status.DRIFT_DETECTED && safeSummary.isPresent())) {
      throw ProactiveContract.violation(ProactiveStableCode.DRIFT_READ_ONLY);
    }
  }

  public static DriftResult clean() {
    return new DriftResult(Status.CLEAN, Optional.empty());
  }

  public static DriftResult detected(String safeSummary) {
    if (safeSummary == null
        || safeSummary.isBlank()
        || safeSummary.codePointCount(0, safeSummary.length()) > 4_000) {
      throw ProactiveContract.violation(ProactiveStableCode.DRIFT_READ_ONLY);
    }
    return new DriftResult(Status.DRIFT_DETECTED, Optional.of(safeSummary));
  }

  public static DriftResult cancelled() {
    return new DriftResult(Status.CANCELLED, Optional.empty());
  }
}
