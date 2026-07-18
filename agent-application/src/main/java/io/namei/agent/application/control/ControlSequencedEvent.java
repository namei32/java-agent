package io.namei.agent.application.control;

import io.namei.agent.kernel.control.ControlEventProjection;
import java.util.Objects;

public record ControlSequencedEvent(long deliverySequence, ControlEventProjection projection) {
  public ControlSequencedEvent {
    if (deliverySequence < 1) {
      throw new IllegalArgumentException("控制面传输序号必须为正数");
    }
    projection = Objects.requireNonNull(projection, "projection");
  }

  @Override
  public String toString() {
    return "ControlSequencedEvent[deliverySequence="
        + deliverySequence
        + ", projection="
        + projection
        + "]";
  }
}
