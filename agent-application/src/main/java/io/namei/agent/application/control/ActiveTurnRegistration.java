package io.namei.agent.application.control;

import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.util.Optional;

public final class ActiveTurnRegistration implements AutoCloseable {
  private static final ActiveTurnRegistration NO_OP = new ActiveTurnRegistration(null, null);

  private final ActiveTurnRegistry registry;
  private final ControlTurnRef turnRef;

  ActiveTurnRegistration(ActiveTurnRegistry registry, ControlTurnRef turnRef) {
    this.registry = registry;
    this.turnRef = turnRef;
  }

  static ActiveTurnRegistration noOp() {
    return NO_OP;
  }

  public boolean registered() {
    return registry != null;
  }

  public Optional<ControlTurnRef> turnRef() {
    return Optional.ofNullable(turnRef);
  }

  public void observe(OutboundMessage message) {
    if (registry != null) {
      registry.observe(turnRef, message);
    }
  }

  public void closeWithoutTerminal() {
    if (registry != null) {
      registry.closeWithoutTerminal(turnRef);
    }
  }

  @Override
  public void close() {
    closeWithoutTerminal();
  }

  @Override
  public String toString() {
    return "ActiveTurnRegistration[registered=" + registered() + ", turnRef=<redacted>]";
  }
}
