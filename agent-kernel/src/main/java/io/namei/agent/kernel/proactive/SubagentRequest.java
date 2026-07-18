package io.namei.agent.kernel.proactive;

import java.util.Objects;

/**
 * A one-shot, parent-bound task. Its shape deliberately contains no tools, network, memory or
 * session.
 */
public record SubagentRequest(ProactiveJobRef parentJobRef, String task, SubagentBudget budget) {
  public SubagentRequest {
    parentJobRef = Objects.requireNonNull(parentJobRef, "parentJobRef");
    budget = Objects.requireNonNull(budget, "budget");
    if (task == null
        || task.isBlank()
        || task.codePointCount(0, task.length()) > budget.maxPromptCharacters()) {
      throw ProactiveContract.violation(ProactiveStableCode.SUBAGENT_BUDGET_EXHAUSTED);
    }
  }
}
