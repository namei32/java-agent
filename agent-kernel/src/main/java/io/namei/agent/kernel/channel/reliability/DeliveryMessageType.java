package io.namei.agent.kernel.channel.reliability;

public enum DeliveryMessageType {
  TURN_COMPLETED,
  TURN_CANCELLED,
  TURN_FAILED,
  SESSION_BUSY,
  NO_ACTIVE_TURN;

  public boolean isTurnTerminal() {
    return this == TURN_COMPLETED || this == TURN_CANCELLED || this == TURN_FAILED;
  }
}
