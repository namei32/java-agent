package io.namei.agent.kernel.proactive;

import java.util.Objects;

/** 一次性、绑定父任务的 Task；其结构有意不包含 Tool、网络、Memory 或 Session。 */
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
