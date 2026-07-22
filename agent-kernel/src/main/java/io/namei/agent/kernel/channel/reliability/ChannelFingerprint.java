package io.namei.agent.kernel.channel.reliability;

import io.namei.agent.kernel.channel.InboundMessage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class ChannelFingerprint {
  public static final String INSTANCE_VERSION = "channel-instance-v1";
  public static final String REQUEST_VERSION = "inbound-request-v1";
  public static final String EVENT_VERSION = "inbound-event-v1";
  public static final String DELIVERY_VERSION = "delivery-fingerprint-v1";
  public static final String PART_VERSION = "delivery-part-v1";

  private ChannelFingerprint() {}

  public static String request(InboundMessage inbound) {
    Objects.requireNonNull(inbound, "inbound");
    return hashFields(
        REQUEST_VERSION,
        Integer.toString(inbound.schemaVersion()),
        inbound.messageId(),
        inbound.sessionId(),
        inbound.route().channel(),
        inbound.route().conversationId(),
        inbound.senderId(),
        inbound.content(),
        inbound.occurredAt().toString());
  }

  public static String event(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      InboxEventKind kind,
      String decisionCode,
      String requestFingerprint) {
    Objects.requireNonNull(instance, "instance");
    externalEventId =
        ChannelReliabilityValues.identifier(externalEventId, "External Event ID", 128);
    ChannelReliabilityValues.externalSequence(externalSequence);
    Objects.requireNonNull(kind, "kind");
    decisionCode = ChannelReliabilityValues.decisionCode(decisionCode, kind != InboxEventKind.TURN);
    requestFingerprint = requestFingerprint == null ? "" : requestFingerprint;
    switch (kind) {
      case TURN -> {
        if (!decisionCode.isEmpty()) {
          throw new IllegalArgumentException("Turn Event 不允许决策码");
        }
        requestFingerprint =
            ChannelReliabilityValues.hash(requestFingerprint, "Request Fingerprint");
      }
      case IGNORED, CONTROL -> {
        if (!requestFingerprint.isEmpty()) {
          throw new IllegalArgumentException("非 Turn Event 不允许 Request Fingerprint");
        }
      }
      case FEEDBACK -> {
        if (!requestFingerprint.isEmpty()) {
          requestFingerprint =
              ChannelReliabilityValues.hash(requestFingerprint, "Request Fingerprint");
        }
      }
    }
    return hashFields(
        EVENT_VERSION,
        instance.channel(),
        instance.value(),
        externalEventId,
        Long.toString(externalSequence),
        kind.name(),
        decisionCode,
        requestFingerprint);
  }

  public static String delivery(
      ChannelInstanceId instance,
      String targetId,
      DeliverySourceKind sourceKind,
      String correlationId,
      DeliveryMessageType messageType,
      String code,
      boolean retryable,
      String chunkAlgorithm,
      List<String> parts) {
    Objects.requireNonNull(instance, "instance");
    targetId = ChannelReliabilityValues.identifier(targetId, "Delivery Target", 256);
    Objects.requireNonNull(sourceKind, "sourceKind");
    correlationId =
        ChannelReliabilityValues.identifier(correlationId, "Delivery Correlation ID", 128);
    Objects.requireNonNull(messageType, "messageType");
    code = ChannelReliabilityValues.decisionCode(code, requiresCode(messageType));
    chunkAlgorithm = ChannelReliabilityValues.algorithm(chunkAlgorithm);
    validateMessageSemantics(sourceKind, messageType, code, retryable);
    List<String> safeParts = copyParts(parts);

    var fields = new ArrayList<String>(11 + safeParts.size() * 2);
    fields.add(DELIVERY_VERSION);
    fields.add(instance.channel());
    fields.add(instance.value());
    fields.add(targetId);
    fields.add(sourceKind.name());
    fields.add(correlationId);
    fields.add(messageType.name());
    fields.add(code);
    fields.add(Boolean.toString(retryable));
    fields.add(chunkAlgorithm);
    fields.add(Integer.toString(safeParts.size()));
    for (int index = 0; index < safeParts.size(); index++) {
      fields.add(Integer.toString(index));
      fields.add(safeParts.get(index));
    }
    return hashFields(fields.toArray(String[]::new));
  }

  public static String part(String deliveryFingerprint, int index, String payload) {
    deliveryFingerprint =
        ChannelReliabilityValues.hash(deliveryFingerprint, "Delivery Fingerprint");
    if (index < 0 || index >= ChannelReliabilityValues.MAX_PARTS) {
      throw new IllegalArgumentException("Delivery Part Index 超出范围");
    }
    payload = ChannelReliabilityValues.payload(payload);
    return hashFields(PART_VERSION, deliveryFingerprint, Integer.toString(index), payload);
  }

  static String hashFields(String... fields) {
    Objects.requireNonNull(fields, "fields");
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        for (String field : fields) {
          Objects.requireNonNull(field, "fingerprint field");
          byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
          output.writeInt(encoded.length);
          output.write(encoded);
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException exception) {
      throw new IllegalStateException("无法生成渠道 Fingerprint", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK 缺少 SHA-256", exception);
    }
  }

  private static boolean requiresCode(DeliveryMessageType messageType) {
    return messageType == DeliveryMessageType.TURN_CANCELLED
        || messageType == DeliveryMessageType.TURN_FAILED;
  }

  private static void validateMessageSemantics(
      DeliverySourceKind sourceKind,
      DeliveryMessageType messageType,
      String code,
      boolean retryable) {
    if (sourceKind == DeliverySourceKind.TURN_TERMINAL && !messageType.isTurnTerminal()) {
      throw new IllegalArgumentException("Turn Terminal 来源与消息类型不一致");
    }
    if (sourceKind == DeliverySourceKind.CHANNEL_FEEDBACK && messageType.isTurnTerminal()) {
      throw new IllegalArgumentException("Channel Feedback 来源与消息类型不一致");
    }
    switch (messageType) {
      case TURN_COMPLETED, SESSION_BUSY, NO_ACTIVE_TURN -> {
        if (!code.isEmpty() || retryable) {
          throw new IllegalArgumentException("消息类型不允许 Code 或 Retryable");
        }
      }
      case TURN_CANCELLED -> {
        if (retryable) {
          throw new IllegalArgumentException("取消消息不能 Retryable");
        }
      }
      case TURN_FAILED -> {
        // retryable 由已经校验的 OutboundMessage 契约推导。
      }
    }
  }

  private static List<String> copyParts(List<String> parts) {
    Objects.requireNonNull(parts, "parts");
    if (parts.isEmpty() || parts.size() > ChannelReliabilityValues.MAX_PARTS) {
      throw new IllegalArgumentException("Delivery Part 数量超出范围");
    }
    return parts.stream().map(ChannelReliabilityValues::payload).toList();
  }
}
