package io.namei.agent.application;

import io.namei.agent.kernel.channel.OutboundMessage;

@FunctionalInterface
public interface OutboundMessageSink {
  void publish(OutboundMessage message);
}
