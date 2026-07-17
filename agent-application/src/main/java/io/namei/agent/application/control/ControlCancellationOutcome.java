package io.namei.agent.application.control;

import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlTurnState;
import java.util.Objects;
import java.util.Optional;

public record ControlCancellationOutcome(
    ControlCancelResult result, Optional<ControlTurnState> state) {
  public ControlCancellationOutcome {
    result = Objects.requireNonNull(result, "result");
    state = Objects.requireNonNull(state, "state");
  }

  static ControlCancellationOutcome active(ControlCancelResult result, ControlTurnState state) {
    return new ControlCancellationOutcome(result, Optional.of(state));
  }

  static ControlCancellationOutcome absent(ControlCancelResult result) {
    return new ControlCancellationOutcome(result, Optional.empty());
  }
}
