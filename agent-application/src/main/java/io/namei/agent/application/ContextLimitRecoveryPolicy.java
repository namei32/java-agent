package io.namei.agent.application;

import io.namei.agent.kernel.prompt.PromptTrimPlan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Pure, bounded candidate policy for R10-P3. It does not issue a model request, read a session, or
 * decide whether retrying is safe after Tool execution.
 */
public final class ContextLimitRecoveryPolicy {
  private final ContextLimitRecoveryMode mode;

  public ContextLimitRecoveryPolicy(ContextLimitRecoveryMode mode) {
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  public static ContextLimitRecoveryPolicy disabled() {
    return new ContextLimitRecoveryPolicy(ContextLimitRecoveryMode.DISABLED);
  }

  public ContextLimitRecoveryMode mode() {
    return mode;
  }

  public List<Plan> plans(int selectedHistorySize) {
    if (selectedHistorySize < 0) {
      throw new IllegalArgumentException("已选历史数量不能为负数");
    }
    if (mode == ContextLimitRecoveryMode.DISABLED) {
      return List.of(new Plan(PromptTrimPlan.FULL, selectedHistorySize));
    }

    var plans = new ArrayList<Plan>();
    var seen = new LinkedHashSet<Plan>();
    for (PromptTrimPlan trimPlan : PromptTrimPlan.values()) {
      add(plans, seen, new Plan(trimPlan, selectedHistorySize));
    }
    add(plans, seen, new Plan(PromptTrimPlan.TRIM_RETRIEVED_MEMORY, selectedHistorySize / 2));
    add(plans, seen, new Plan(PromptTrimPlan.TRIM_RETRIEVED_MEMORY, 0));
    return List.copyOf(plans);
  }

  private static void add(List<Plan> plans, LinkedHashSet<Plan> seen, Plan candidate) {
    if (seen.add(candidate)) {
      plans.add(candidate);
    }
  }

  public record Plan(PromptTrimPlan trimPlan, int historySize) {
    public Plan {
      trimPlan = Objects.requireNonNull(trimPlan, "trimPlan");
      if (historySize < 0) {
        throw new IllegalArgumentException("历史数量不能为负数");
      }
    }
  }
}
