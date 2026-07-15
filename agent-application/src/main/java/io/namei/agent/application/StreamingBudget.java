package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import java.util.Objects;

final class StreamingBudget {
  private final ModelStreamingSettings settings;
  private int eventCount;
  private long codePointCount;

  StreamingBudget(ModelStreamingSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  void accept(String delta) {
    if (delta == null || delta.isEmpty()) {
      throw new InvalidModelResponseException("模型流 Delta 不能为空");
    }
    int nextEventCount = eventCount + 1;
    long nextCodePointCount = codePointCount + (long) delta.codePointCount(0, delta.length());
    if (nextEventCount > settings.maxDeltaEvents()
        || nextCodePointCount > settings.maxDeltaCodePoints()) {
      throw new ModelStreamLimitExceededException("模型流超过安全上限");
    }
    eventCount = nextEventCount;
    codePointCount = nextCodePointCount;
  }
}
