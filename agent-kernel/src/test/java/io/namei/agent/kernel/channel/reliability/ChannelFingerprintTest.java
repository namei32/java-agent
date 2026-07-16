package io.namei.agent.kernel.channel.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelFingerprintTest {
  private static final ChannelInstanceId INSTANCE =
      ChannelInstanceId.derive("telegram", "123456789");

  @Test
  void lengthPrefixesPreventFieldBoundaryCollisions() {
    String first = ChannelFingerprint.event(INSTANCE, "AB", 1, InboxEventKind.IGNORED, "C", "");
    String second = ChannelFingerprint.event(INSTANCE, "A", 1, InboxEventKind.IGNORED, "BC", "");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void validatesEventDecisionAndRequestFingerprintShapes() {
    assertThatThrownBy(
            () -> ChannelFingerprint.event(INSTANCE, "500", 500, InboxEventKind.TURN, "", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ChannelFingerprint.event(
                    INSTANCE, "500", 500, InboxEventKind.IGNORED, "NOT_ALLOWED", "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ChannelFingerprint.event(
                    INSTANCE, "500", Long.MAX_VALUE, InboxEventKind.CONTROL, "CANCEL", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesDeliverySemanticsAndPartBudget() {
    assertThatThrownBy(
            () ->
                ChannelFingerprint.delivery(
                    INSTANCE,
                    "10001",
                    DeliverySourceKind.TURN_TERMINAL,
                    "turn-1",
                    DeliveryMessageType.TURN_COMPLETED,
                    "unexpected",
                    false,
                    "telegram-text-chunks-v1",
                    List.of("ok")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ChannelFingerprint.delivery(
                    INSTANCE,
                    "10001",
                    DeliverySourceKind.TURN_TERMINAL,
                    "turn-1",
                    DeliveryMessageType.TURN_FAILED,
                    "MODEL_TIMEOUT",
                    true,
                    "telegram-text-chunks-v1",
                    java.util.Collections.nCopies(17, "x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ChannelFingerprint.delivery(
                    INSTANCE,
                    "10001",
                    DeliverySourceKind.CHANNEL_FEEDBACK,
                    "event-1",
                    DeliveryMessageType.SESSION_BUSY,
                    "",
                    true,
                    "telegram-text-chunks-v1",
                    List.of("busy")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deliveryEnvelopeDefensivelyCopiesPartsAndChecksHash() {
    var source = new java.util.ArrayList<>(List.of("first"));
    DeliveryEnvelope envelope =
        DeliveryEnvelope.create(
            INSTANCE,
            "delivery-1",
            "10001",
            DeliverySourceKind.TURN_TERMINAL,
            "turn-1",
            DeliveryMessageType.TURN_COMPLETED,
            "",
            false,
            "telegram-text-chunks-v1",
            source);

    source.set(0, "changed");

    assertThat(envelope.parts()).extracting(DeliveryPartPayload::payload).containsExactly("first");
    assertThat(envelope.parts().getFirst().payloadFingerprint()).matches("[0-9a-f]{64}");
    assertThat(envelope.payloadFingerprint())
        .isEqualTo(
            ChannelFingerprint.delivery(
                INSTANCE,
                "10001",
                DeliverySourceKind.TURN_TERMINAL,
                "turn-1",
                DeliveryMessageType.TURN_COMPLETED,
                "",
                false,
                "telegram-text-chunks-v1",
                List.of("first")));
    assertThatThrownBy(() -> envelope.parts().add(envelope.parts().getFirst()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void requestFingerprintExcludesTurnIdButBindsTrustedFields() {
    InboundMessage first = inbound("turn-one", "content");
    InboundMessage second = inbound("turn-two", "content");
    InboundMessage changed = inbound("turn-one", "changed");

    assertThat(ChannelFingerprint.request(first)).isEqualTo(ChannelFingerprint.request(second));
    assertThat(ChannelFingerprint.request(first)).isNotEqualTo(ChannelFingerprint.request(changed));
  }

  private static InboundMessage inbound(String turnId, String content) {
    return new InboundMessage(
        1,
        "telegram:10001:42",
        turnId,
        "telegram:10001",
        new MessageRoute("telegram", "10001"),
        "10001",
        content,
        Instant.parse("2026-07-16T00:00:00Z"));
  }
}
