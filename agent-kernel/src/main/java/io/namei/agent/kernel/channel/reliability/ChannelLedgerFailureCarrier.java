package io.namei.agent.kernel.channel.reliability;

public interface ChannelLedgerFailureCarrier {
  ChannelLedgerFailureKind ledgerFailureKind();
}
