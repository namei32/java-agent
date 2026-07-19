package io.namei.agent.kernel.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderCacheUsageTest {
  @Test
  void acceptsZeroCacheHitAsARealObservedSample() {
    assertThat(new ProviderCacheUsage(10, 0)).isEqualTo(new ProviderCacheUsage(10, 0));
  }

  @Test
  void rejectsImpossibleOrNegativeUsageBeforeItCanCrossTheAdapterBoundary() {
    assertThatThrownBy(() -> new ProviderCacheUsage(-1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProviderCacheUsage(1, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProviderCacheUsage(1, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
