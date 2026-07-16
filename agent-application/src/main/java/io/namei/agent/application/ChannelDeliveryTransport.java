package io.namei.agent.application;

@FunctionalInterface
public interface ChannelDeliveryTransport {
  ChannelDeliveryResult send(ChannelDeliveryRequest request);
}
