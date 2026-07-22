package io.namei.agent.kernel.model;

/**
 * 单个 Provider 响应 Prompt/Cache Read Usage 的可信无内容投影。
 *
 * <p>它有意不包含 Provider Native Payload、Model Identifier、Request Identifier、Cache Key 或消息数据。
 */
public record ProviderCacheUsage(long promptTokens, long cacheHitTokens) {
  public ProviderCacheUsage {
    if (promptTokens < 0) {
      throw new IllegalArgumentException("Provider prompt token 数不能为负数");
    }
    if (cacheHitTokens < 0) {
      throw new IllegalArgumentException("Provider cache hit token 数不能为负数");
    }
    if (cacheHitTokens > promptTokens) {
      throw new IllegalArgumentException("Provider cache hit token 数不能大于 prompt token 数");
    }
  }
}
