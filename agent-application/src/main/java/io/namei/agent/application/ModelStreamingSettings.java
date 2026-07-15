package io.namei.agent.application;

import io.namei.agent.kernel.channel.MessageContract;

public record ModelStreamingSettings(int maxDeltaEvents, int maxDeltaCodePoints) {
  public static final int MAX_DELTA_EVENTS = 10_000;

  public ModelStreamingSettings {
    if (maxDeltaEvents < 1 || maxDeltaEvents > MAX_DELTA_EVENTS) {
      throw new IllegalArgumentException("maxDeltaEvents 必须在 1 到 10000 之间");
    }
    if (maxDeltaCodePoints < 1 || maxDeltaCodePoints > MessageContract.MAX_CONTENT_CHARACTERS) {
      throw new IllegalArgumentException("maxDeltaCodePoints 必须在 1 到 32000 之间");
    }
  }

  public static ModelStreamingSettings defaults() {
    return new ModelStreamingSettings(2_048, MessageContract.MAX_CONTENT_CHARACTERS);
  }
}
