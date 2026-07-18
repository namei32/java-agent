package io.namei.agent.kernel.proactive;

import java.util.Objects;
import java.util.Optional;

public record SubagentResult(Status status, Optional<String> text) {
  public enum Status {
    COMPLETED,
    BUDGET_EXHAUSTED,
    CANCELLED
  }

  public SubagentResult {
    status = Objects.requireNonNull(status, "status");
    text = Objects.requireNonNull(text, "text");
    if ((status == Status.COMPLETED && text.isEmpty())
        || (status != Status.COMPLETED && text.isPresent())) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
  }

  public static SubagentResult completed(String text) {
    return new SubagentResult(Status.COMPLETED, Optional.of(Objects.requireNonNull(text, "text")));
  }

  public static SubagentResult budgetExhausted() {
    return new SubagentResult(Status.BUDGET_EXHAUSTED, Optional.empty());
  }

  public static SubagentResult cancelled() {
    return new SubagentResult(Status.CANCELLED, Optional.empty());
  }
}
