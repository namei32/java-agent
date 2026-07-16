package io.namei.agent.kernel.channel.reliability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record DeliveryEnvelope(
    ChannelInstanceId instance,
    String deliveryId,
    String targetId,
    DeliverySourceKind sourceKind,
    String correlationId,
    DeliveryMessageType messageType,
    String code,
    boolean retryable,
    String chunkAlgorithm,
    String payloadFingerprint,
    List<DeliveryPartPayload> parts) {
  public DeliveryEnvelope {
    Objects.requireNonNull(instance, "instance");
    deliveryId = ChannelReliabilityValues.identifier(deliveryId, "Delivery ID", 128);
    targetId = ChannelReliabilityValues.identifier(targetId, "Delivery Target", 256);
    Objects.requireNonNull(sourceKind, "sourceKind");
    correlationId =
        ChannelReliabilityValues.identifier(correlationId, "Delivery Correlation ID", 128);
    Objects.requireNonNull(messageType, "messageType");
    code = ChannelReliabilityValues.decisionCode(code, requiresCode(messageType));
    chunkAlgorithm = ChannelReliabilityValues.algorithm(chunkAlgorithm);
    payloadFingerprint =
        ChannelReliabilityValues.hash(payloadFingerprint, "Delivery Payload Fingerprint");
    Objects.requireNonNull(parts, "parts");
    parts = List.copyOf(parts);
    if (parts.isEmpty() || parts.size() > ChannelReliabilityValues.MAX_PARTS) {
      throw new IllegalArgumentException("Delivery Part 数量超出范围");
    }
    for (int index = 0; index < parts.size(); index++) {
      DeliveryPartPayload part = Objects.requireNonNull(parts.get(index), "part");
      if (part.index() != index) {
        throw new IllegalArgumentException("Delivery Part Index 必须连续");
      }
    }
    String expected =
        ChannelFingerprint.delivery(
            instance,
            targetId,
            sourceKind,
            correlationId,
            messageType,
            code,
            retryable,
            chunkAlgorithm,
            parts.stream().map(DeliveryPartPayload::payload).toList());
    if (!expected.equals(payloadFingerprint)) {
      throw new IllegalArgumentException("Delivery Payload Fingerprint 不匹配");
    }
    for (DeliveryPartPayload part : parts) {
      String expectedPart =
          ChannelFingerprint.part(payloadFingerprint, part.index(), part.payload());
      if (!expectedPart.equals(part.payloadFingerprint())) {
        throw new IllegalArgumentException("Delivery Part Fingerprint 不匹配");
      }
    }
  }

  public static DeliveryEnvelope create(
      ChannelInstanceId instance,
      String deliveryId,
      String targetId,
      DeliverySourceKind sourceKind,
      String correlationId,
      DeliveryMessageType messageType,
      String code,
      boolean retryable,
      String chunkAlgorithm,
      List<String> payloads) {
    Objects.requireNonNull(payloads, "payloads");
    List<String> copy = List.copyOf(payloads);
    String fingerprint =
        ChannelFingerprint.delivery(
            instance,
            targetId,
            sourceKind,
            correlationId,
            messageType,
            code,
            retryable,
            chunkAlgorithm,
            copy);
    var parts = new ArrayList<DeliveryPartPayload>(copy.size());
    for (int index = 0; index < copy.size(); index++) {
      parts.add(DeliveryPartPayload.create(fingerprint, index, copy.get(index)));
    }
    return new DeliveryEnvelope(
        instance,
        deliveryId,
        targetId,
        sourceKind,
        correlationId,
        messageType,
        code,
        retryable,
        chunkAlgorithm,
        fingerprint,
        parts);
  }

  private static boolean requiresCode(DeliveryMessageType messageType) {
    return messageType == DeliveryMessageType.TURN_CANCELLED
        || messageType == DeliveryMessageType.TURN_FAILED;
  }

  @Override
  public String toString() {
    return "DeliveryEnvelope[sourceKind="
        + sourceKind
        + ", messageType="
        + messageType
        + ", partCount="
        + parts.size()
        + ", sensitiveFields=<redacted>]";
  }
}
