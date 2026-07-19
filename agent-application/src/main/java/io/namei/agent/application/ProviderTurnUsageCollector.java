package io.namei.agent.application;

import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.ProviderTurnUsage;

/**
 * Turn-local collector; it intentionally retains no model content, identity, or provider-native
 * data.
 */
final class ProviderTurnUsageCollector {
  private int modelCallCount;
  private long cachePromptTokens;
  private long cacheHitTokens;
  private boolean hasCacheUsage;
  private boolean cacheUsageOverflowed;

  void accept(ChatModelResponse response) {
    modelCallCount = Math.incrementExact(modelCallCount);
    response
        .cacheUsage()
        .ifPresent(
            usage -> {
              if (cacheUsageOverflowed) {
                return;
              }
              try {
                cachePromptTokens = Math.addExact(cachePromptTokens, usage.promptTokens());
                cacheHitTokens = Math.addExact(cacheHitTokens, usage.cacheHitTokens());
                hasCacheUsage = true;
              } catch (ArithmeticException ignored) {
                cachePromptTokens = 0;
                cacheHitTokens = 0;
                hasCacheUsage = false;
                cacheUsageOverflowed = true;
              }
            });
  }

  ProviderTurnUsage snapshot() {
    if (!hasCacheUsage || cacheUsageOverflowed) {
      return new ProviderTurnUsage(modelCallCount, null, null);
    }
    return new ProviderTurnUsage(modelCallCount, cachePromptTokens, cacheHitTokens);
  }
}
