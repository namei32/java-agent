package io.namei.agent.application;

import java.util.Objects;

public interface TurnCancellation {
  boolean isCancellationRequested();

  Registration onCancellation(Runnable callback);

  static TurnCancellation none() {
    return NeverCancelled.INSTANCE;
  }

  @FunctionalInterface
  interface Registration extends AutoCloseable {
    @Override
    void close();
  }

  enum NeverCancelled implements TurnCancellation {
    INSTANCE;

    @Override
    public boolean isCancellationRequested() {
      return false;
    }

    @Override
    public Registration onCancellation(Runnable callback) {
      Objects.requireNonNull(callback, "callback");
      return () -> {};
    }
  }
}
