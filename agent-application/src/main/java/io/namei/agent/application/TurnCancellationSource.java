package io.namei.agent.application;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TurnCancellationSource {
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CopyOnWriteArrayList<CallbackRegistration> callbacks = new CopyOnWriteArrayList<>();
  private final TurnCancellation token = new SourceToken();

  public TurnCancellation token() {
    return token;
  }

  public boolean cancel() {
    if (!cancelled.compareAndSet(false, true)) {
      return false;
    }
    for (CallbackRegistration callback : callbacks) {
      callback.invoke();
    }
    callbacks.clear();
    return true;
  }

  private final class SourceToken implements TurnCancellation {
    @Override
    public boolean isCancellationRequested() {
      return cancelled.get();
    }

    @Override
    public Registration onCancellation(Runnable callback) {
      Objects.requireNonNull(callback, "callback");
      var registration = new CallbackRegistration(callback);
      if (cancelled.get()) {
        registration.invoke();
        return registration;
      }
      callbacks.add(registration);
      if (cancelled.get() && callbacks.remove(registration)) {
        registration.invoke();
      }
      return registration;
    }
  }

  private final class CallbackRegistration implements TurnCancellation.Registration {
    private final Runnable callback;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private CallbackRegistration(Runnable callback) {
      this.callback = callback;
    }

    private void invoke() {
      if (!active.compareAndSet(true, false)) {
        return;
      }
      try {
        callback.run();
      } catch (RuntimeException ignored) {
        // 取消回调相互隔离，单个失败不能阻止其余资源被通知。
      }
    }

    @Override
    public void close() {
      active.set(false);
      callbacks.remove(this);
    }
  }
}
