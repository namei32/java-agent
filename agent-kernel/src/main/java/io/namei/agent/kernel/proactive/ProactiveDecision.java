package io.namei.agent.kernel.proactive;

import java.util.Objects;
import java.util.Optional;

public record ProactiveDecision(Kind kind, Optional<ProactiveStableCode> code) {
  public enum Kind {
    SKIP,
    REQUESTED
  }

  public ProactiveDecision {
    kind = Objects.requireNonNull(kind, "kind");
    code = Objects.requireNonNull(code, "code");
    if ((kind == Kind.REQUESTED && code.isPresent())
        || (kind == Kind.SKIP && (code.isEmpty() || !skipCode(code.orElseThrow())))) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
  }

  public static ProactiveDecision requested() {
    return new ProactiveDecision(Kind.REQUESTED, Optional.empty());
  }

  public static ProactiveDecision skipped(ProactiveStableCode code) {
    return new ProactiveDecision(Kind.SKIP, Optional.of(Objects.requireNonNull(code, "code")));
  }

  private static boolean skipCode(ProactiveStableCode code) {
    return switch (code) {
      case PROACTIVE_DISABLED,
          PROACTIVE_BUDGET_EXHAUSTED,
          PROACTIVE_COOLDOWN,
          PROACTIVE_TARGET_BUSY,
          PROACTIVE_DUPLICATE,
          PROACTIVE_LEASE_LOST,
          PROACTIVE_NO_SOURCE,
          PROACTIVE_SOURCE_INVALID,
          PROACTIVE_NO_DRIFT ->
          true;
      default -> false;
    };
  }
}
