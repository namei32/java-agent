package io.namei.agent.kernel.channel;

public enum TurnCancellationCode {
  REQUESTED,
  CHANNEL_DISCONNECTED,
  BACKPRESSURE_EXCEEDED,
  SHUTDOWN;

  static TurnCancellationCode parse(String code) {
    for (TurnCancellationCode candidate : values()) {
      if (candidate.name().equals(code)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("未知 Turn 取消码");
  }
}
