package io.namei.agent.application.control;

import io.namei.agent.application.OutboundMessageSink;
import io.namei.agent.kernel.channel.OutboundMessage;
import java.util.Objects;

public final class ObservedOutboundMessageSink implements OutboundMessageSink {
  private final OutboundMessageSink primary;
  private final ActiveTurnRegistration registration;

  public ObservedOutboundMessageSink(
      OutboundMessageSink primary, ActiveTurnRegistration registration) {
    this.primary = Objects.requireNonNull(primary, "primary");
    this.registration = Objects.requireNonNull(registration, "registration");
  }

  @Override
  public void publish(OutboundMessage message) {
    primary.publish(message);
    registration.observeSafely(message);
  }
}
