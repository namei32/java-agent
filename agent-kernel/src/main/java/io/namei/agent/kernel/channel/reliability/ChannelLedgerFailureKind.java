package io.namei.agent.kernel.channel.reliability;

public enum ChannelLedgerFailureKind {
  UNAVAILABLE,
  IDEMPOTENCY_CONFLICT,
  CAPACITY_EXCEEDED,
  STALE_WRITE
}
