package io.namei.agent.application;

@FunctionalInterface
public interface ChannelDeliveryWakeSignal {
  void signal();
}
