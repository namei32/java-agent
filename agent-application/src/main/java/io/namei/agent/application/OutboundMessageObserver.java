package io.namei.agent.application;

import io.namei.agent.kernel.channel.OutboundMessage;

@FunctionalInterface
public interface OutboundMessageObserver {
  void onTerminal(OutboundMessage message);

  static OutboundMessageObserver noop() {
    return message -> {};
  }
}
