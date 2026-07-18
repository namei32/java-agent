package io.namei.agent.application.control;

public record ControlEventHubSnapshot(
    int subscriberCount,
    int maxSubscriberQueueDepth,
    int subscriberBufferCapacity,
    long slowConsumerDisconnects) {
  public ControlEventHubSnapshot {
    if (subscriberCount < 0
        || maxSubscriberQueueDepth < 0
        || subscriberBufferCapacity < 1
        || slowConsumerDisconnects < 0) {
      throw new IllegalArgumentException("控制面 Event Hub 指标无效");
    }
  }
}
