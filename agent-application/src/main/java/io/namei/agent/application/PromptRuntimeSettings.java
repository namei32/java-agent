package io.namei.agent.application;

import io.namei.agent.kernel.prompt.PromptBudget;
import io.namei.agent.kernel.prompt.PromptMode;
import java.time.ZoneId;
import java.util.Objects;

/** Bootstrap 完成严格配置绑定后提供的仅 Runtime 设置。 */
public record PromptRuntimeSettings(PromptMode mode, PromptBudget budget, ZoneId zoneId) {
  public PromptRuntimeSettings {
    mode = Objects.requireNonNull(mode, "mode");
    budget = Objects.requireNonNull(budget, "budget");
    zoneId = Objects.requireNonNull(zoneId, "zoneId");
  }

  public static PromptRuntimeSettings minimalDefaults() {
    return new PromptRuntimeSettings(
        PromptMode.MINIMAL, new PromptBudget(100_000, 100_000, 200_000, 9), ZoneId.of("UTC"));
  }
}
