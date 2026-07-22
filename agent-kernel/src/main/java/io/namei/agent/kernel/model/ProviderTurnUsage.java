package io.namei.agent.kernel.model;

/** 仅在 Application Turn 成功提交后发出的无内容聚合数据。 */
public record ProviderTurnUsage(int modelCallCount, Long cachePromptTokens, Long cacheHitTokens) {
  public ProviderTurnUsage {
    if (modelCallCount < 0) {
      throw new IllegalArgumentException("模型调用次数不能为负数");
    }
    if ((cachePromptTokens == null) != (cacheHitTokens == null)) {
      throw new IllegalArgumentException("缓存 prompt 与 hit 用量必须同时存在或同时缺失");
    }
    if (cachePromptTokens != null) {
      if (cachePromptTokens < 0 || cacheHitTokens < 0) {
        throw new IllegalArgumentException("缓存用量不能为负数");
      }
      if (cacheHitTokens > cachePromptTokens) {
        throw new IllegalArgumentException("缓存 hit 用量不能大于 prompt 用量");
      }
    }
  }
}
