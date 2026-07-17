package io.namei.agent.application.control;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import java.util.Objects;

public interface ControlCancellationHandle {
  boolean requestCancellation();

  boolean isCancellationRequested();

  TurnCancellationCode reason();

  static ControlCancellationHandle from(TurnCancellationSource source) {
    Objects.requireNonNull(source, "source");
    return new ControlCancellationHandle() {
      @Override
      public boolean requestCancellation() {
        return source.cancel(TurnCancellationCode.REQUESTED);
      }

      @Override
      public boolean isCancellationRequested() {
        return source.token().isCancellationRequested();
      }

      @Override
      public TurnCancellationCode reason() {
        return source.token().reason();
      }
    };
  }
}
