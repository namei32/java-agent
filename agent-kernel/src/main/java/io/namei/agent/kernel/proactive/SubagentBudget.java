package io.namei.agent.kernel.proactive;

import java.time.Duration;
import java.util.Objects;

public record SubagentBudget(
    int maxPromptCharacters, int maxResultCharacters, int maxSteps, Duration timeout) {
  public SubagentBudget {
    Objects.requireNonNull(timeout, "timeout");
    if (maxPromptCharacters < 1
        || maxPromptCharacters > 4_000
        || maxResultCharacters < 1
        || maxResultCharacters > 8_000
        || maxSteps < 1
        || maxSteps > 4
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
      throw ProactiveContract.violation(ProactiveStableCode.SUBAGENT_BUDGET_EXHAUSTED);
    }
  }
}
