package io.namei.agent.kernel.channel.reliability;

public record DeliveryPartPayload(int index, String payload, String payloadFingerprint) {
  public DeliveryPartPayload {
    if (index < 0 || index >= ChannelReliabilityValues.MAX_PARTS) {
      throw new IllegalArgumentException("Delivery Part Index 超出范围");
    }
    payload = ChannelReliabilityValues.payload(payload);
    payloadFingerprint =
        ChannelReliabilityValues.hash(payloadFingerprint, "Part Payload Fingerprint");
  }

  static DeliveryPartPayload create(String deliveryFingerprint, int index, String payload) {
    return new DeliveryPartPayload(
        index, payload, ChannelFingerprint.part(deliveryFingerprint, index, payload));
  }

  @Override
  public String toString() {
    return "DeliveryPartPayload[index=" + index + ", sensitiveFields=<redacted>]";
  }
}
