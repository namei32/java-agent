package io.namei.agent.application;

public enum ReliableChannelFailure {
  LEDGER_UNAVAILABLE("CHANNEL_LEDGER_UNAVAILABLE"),
  IDEMPOTENCY_CONFLICT("CHANNEL_IDEMPOTENCY_CONFLICT"),
  LEDGER_CAPACITY_EXCEEDED("CHANNEL_LEDGER_CAPACITY_EXCEEDED"),
  LEDGER_STALE_WRITE("CHANNEL_LEDGER_STALE_WRITE"),
  TURN_START_FAILED("CHANNEL_TURN_START_FAILED");

  private final String code;

  ReliableChannelFailure(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
