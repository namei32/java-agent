package io.namei.agent.kernel.concurrent;

import io.namei.agent.kernel.error.TurnCancelledException;
import java.util.Objects;

public interface CancellationSignal {
  boolean isCancellationRequested();

  Registration onCancellation(Runnable callback);

  default void throwIfCancellationRequested() {
    if (isCancellationRequested()) {
      throw new TurnCancelledException("当前操作已取消");
    }
  }

  static CancellationSignal none() {
    return NeverCancelled.INSTANCE;
  }

  @FunctionalInterface
  interface Registration extends AutoCloseable {
    @Override
    void close();
  }

  enum NeverCancelled implements CancellationSignal {
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
