package io.namei.agent.application;

import io.namei.agent.kernel.channel.InboundMessage;

@FunctionalInterface
public interface ReliableTurnProcessor {
  void process(InboundMessage inbound, TurnCancellation cancellation);
}
