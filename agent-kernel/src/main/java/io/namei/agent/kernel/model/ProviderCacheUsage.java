package io.namei.agent.kernel.model;

/**
 * A trusted, content-free projection of one provider response's prompt/cache-read usage.
 *
 * <p>It deliberately has no provider-native payload, model identifier, request identifier, cache
 * key, or message data.
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
