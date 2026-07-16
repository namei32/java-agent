package io.namei.agent.bootstrap.telegram;

import java.time.Duration;

@FunctionalInterface
public interface ChannelSleeper {
  void sleep(Duration duration) throws InterruptedException;

  static ChannelSleeper system() {
    return duration -> {
      long millis = duration.toMillis();
      int nanos = (int) (duration.toNanosPart() % 1_000_000L);
      Thread.sleep(millis, nanos);
    };
  }
}
