package io.namei.agent.application;

import io.namei.agent.kernel.channel.TurnCancellationCode;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TurnCancellationSource {
  private final AtomicReference<TurnCancellationCode> reason = new AtomicReference<>();
  private final CopyOnWriteArrayList<CallbackRegistration> callbacks = new CopyOnWriteArrayList<>();
  private final TurnCancellation token = new SourceToken();

  public TurnCancellation token() {
    return token;
  }

  public boolean cancel() {
    return cancel(TurnCancellationCode.REQUESTED);
  }

  public boolean cancel(TurnCancellationCode cancellationCode) {
    Objects.requireNonNull(cancellationCode, "cancellationCode");
    if (!reason.compareAndSet(null, cancellationCode)) {
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
      return reason.get() != null;
    }

    @Override
    public TurnCancellationCode reason() {
      TurnCancellationCode current = reason.get();
      return current == null ? TurnCancellationCode.REQUESTED : current;
    }

    @Override
    public Registration onCancellation(Runnable callback) {
      Objects.requireNonNull(callback, "callback");
      var registration = new CallbackRegistration(callback);
      if (reason.get() != null) {
        registration.invoke();
        return registration;
      }
      callbacks.add(registration);
      if (reason.get() != null && callbacks.remove(registration)) {
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
