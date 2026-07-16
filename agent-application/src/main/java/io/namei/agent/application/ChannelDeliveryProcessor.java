package io.namei.agent.application;

@FunctionalInterface
public interface ChannelDeliveryProcessor {
  ChannelDeliveryStep processNext();
}
