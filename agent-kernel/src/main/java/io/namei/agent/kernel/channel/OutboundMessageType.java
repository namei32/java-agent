package io.namei.agent.kernel.channel;

public enum OutboundMessageType {
  TURN_STARTED,
  CONTENT_DELTA,
  TURN_COMPLETED,
  TURN_CANCELLED,
  TURN_FAILED;

  public boolean isTerminal() {
    return this == TURN_COMPLETED || this == TURN_CANCELLED || this == TURN_FAILED;
  }
}
