package io.namei.agent.kernel.channel.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ChannelReliableDeliveryContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void enforcesEveryReliableDeliveryFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("channels/channel-reliable-delivery-v1.json"));
    JsonNode cases = fixture.path("cases");

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(cases).hasSize(40);

    for (JsonNode testCase : cases) {
      String caseId = testCase.path("id").asString();
      JsonNode expected = testCase.path("expected");
      try {
        Object actual = evaluate(testCase, fixture.path("defaults"));
        if (expected.has("accepted") && !expected.path("accepted").asBoolean()) {
          fail("Case 应被拒绝: " + caseId);
        }
        if (expected.has("value")) {
          assertThat(actual).as(caseId).isEqualTo(expected.path("value").asString());
        } else if (expected.has("allowed")) {
          assertThat(actual).as(caseId).isEqualTo(expected.path("allowed").asBoolean());
        }
      } catch (IllegalArgumentException exception) {
        if (!expected.has("accepted") || expected.path("accepted").asBoolean()) {
          throw new AssertionError("Case 不应被拒绝: " + caseId, exception);
        }
      }
    }
  }

  @Test
  void fixesStateSetsAndNarrowLedgerOperations() {
    assertThat(TurnClaimState.values())
        .containsExactly(
            TurnClaimState.RESERVED,
            TurnClaimState.START_RETRYABLE,
            TurnClaimState.RUNNING,
            TurnClaimState.TERMINAL_RECORDED,
            TurnClaimState.EXECUTION_UNKNOWN);
    assertThat(DeliveryState.values())
        .containsExactly(
            DeliveryState.PENDING,
            DeliveryState.DELIVERING,
            DeliveryState.DELIVERED,
            DeliveryState.FAILED,
            DeliveryState.UNKNOWN);
    assertThat(DeliveryPartState.values())
        .containsExactly(
            DeliveryPartState.PENDING,
            DeliveryPartState.IN_FLIGHT,
            DeliveryPartState.RETRY_WAIT,
            DeliveryPartState.DELIVERED,
            DeliveryPartState.FAILED,
            DeliveryPartState.UNKNOWN);
    assertThat(DeliveryAttemptOutcome.values())
        .containsExactly(
            DeliveryAttemptOutcome.STARTED,
            DeliveryAttemptOutcome.SUCCEEDED,
            DeliveryAttemptOutcome.RETRYABLE_REJECTED,
            DeliveryAttemptOutcome.PERMANENT_REJECTED,
            DeliveryAttemptOutcome.UNKNOWN);

    Set<String> operations =
        Arrays.stream(ChannelLedgerPort.class.getDeclaredMethods())
            .map(method -> method.getName())
            .collect(Collectors.toSet());
    assertThat(operations)
        .containsExactlyInAnyOrder(
            "recordEvent",
            "startTurn",
            "recordTerminal",
            "claimNextDelivery",
            "recordDeliveryOutcome",
            "recover",
            "cleanup",
            "snapshot");
    assertThat(
            Arrays.stream(ChannelLedgerPort.class.getDeclaredMethods())
                .flatMap(
                    method ->
                        java.util.stream.Stream.concat(
                            Arrays.stream(method.getParameterTypes()),
                            java.util.stream.Stream.of(method.getReturnType())))
                .map(Class::getName))
        .noneMatch(
            name ->
                name.startsWith("java.sql.")
                    || name.startsWith("org.springframework.")
                    || name.contains("telegram"));
  }

  @Test
  void redactsStableIdentifiersAndPayloadShapes() {
    ChannelInstanceId instance = ChannelInstanceId.derive("telegram", "123456789");
    DeliveryEnvelope delivery =
        DeliveryEnvelope.create(
            instance,
            "delivery-secret",
            "target-secret",
            DeliverySourceKind.TURN_TERMINAL,
            "turn-secret",
            DeliveryMessageType.TURN_COMPLETED,
            "",
            false,
            "telegram-text-chunks-v1",
            List.of("payload-secret"));

    assertThat(instance.toString()).doesNotContain(instance.value(), "123456789");
    assertThat(delivery.toString())
        .doesNotContain(
            "delivery-secret", "target-secret", "turn-secret", "payload-secret", instance.value());
    assertThat(delivery.parts().getFirst().toString()).doesNotContain("payload-secret");
  }

  private static Object evaluate(JsonNode testCase, JsonNode defaults) {
    JsonNode input = testCase.path("input");
    return switch (testCase.path("component").asString()) {
      case "instance" ->
          ChannelInstanceId.derive(
                  text(input, defaults, "channel"), text(input, defaults, "trustedInstanceKey"))
              .value();
      case "request-fingerprint" -> ChannelFingerprint.request(inbound(input, defaults));
      case "event-fingerprint" -> eventFingerprint(input, defaults);
      case "delivery-fingerprint" -> deliveryFingerprint(input, defaults);
      case "transition" -> transition(input);
      default -> throw new IllegalArgumentException("未知 Fixture Component");
    };
  }

  private static String eventFingerprint(JsonNode input, JsonNode defaults) {
    ChannelInstanceId instance =
        ChannelInstanceId.derive(
            text(input, defaults, "channel"), text(input, defaults, "trustedInstanceKey"));
    String requestFingerprint =
        input.path("includeRequestFingerprint").isBoolean()
                && !input.path("includeRequestFingerprint").asBoolean()
            ? ""
            : ChannelFingerprint.request(inbound(input, defaults));
    return ChannelFingerprint.event(
        instance,
        text(input, defaults, "externalEventId"),
        longValue(input, defaults, "externalSequence"),
        InboxEventKind.valueOf(text(input, defaults, "eventKind")),
        text(input, defaults, "decisionCode"),
        requestFingerprint);
  }

  private static String deliveryFingerprint(JsonNode input, JsonNode defaults) {
    ChannelInstanceId instance =
        ChannelInstanceId.derive(
            text(input, defaults, "channel"), text(input, defaults, "trustedInstanceKey"));
    return ChannelFingerprint.delivery(
        instance,
        text(input, defaults, "targetId"),
        DeliverySourceKind.valueOf(text(input, defaults, "sourceKind")),
        text(input, defaults, "correlationId"),
        DeliveryMessageType.valueOf(text(input, defaults, "messageType")),
        text(input, defaults, "code"),
        bool(input, defaults, "retryable"),
        text(input, defaults, "chunkAlgorithm"),
        strings(value(input, defaults, "parts")));
  }

  private static boolean transition(JsonNode input) {
    String from = input.path("from").asString();
    String to = input.path("to").asString();
    return switch (input.path("machine").asString()) {
      case "CLAIM" ->
          ChannelReliabilityRules.canTransition(
              TurnClaimState.valueOf(from), TurnClaimState.valueOf(to));
      case "DELIVERY" ->
          ChannelReliabilityRules.canTransition(
              DeliveryState.valueOf(from), DeliveryState.valueOf(to));
      case "PART" ->
          ChannelReliabilityRules.canTransition(
              DeliveryPartState.valueOf(from), DeliveryPartState.valueOf(to));
      case "ATTEMPT" ->
          ChannelReliabilityRules.canTransition(
              DeliveryAttemptOutcome.valueOf(from), DeliveryAttemptOutcome.valueOf(to));
      default -> throw new IllegalArgumentException("未知状态机");
    };
  }

  private static InboundMessage inbound(JsonNode input, JsonNode defaults) {
    return new InboundMessage(
        integer(input, defaults, "schemaVersion"),
        text(input, defaults, "messageId"),
        text(input, defaults, "turnId"),
        text(input, defaults, "sessionId"),
        new MessageRoute(text(input, defaults, "channel"), text(input, defaults, "conversationId")),
        text(input, defaults, "senderId"),
        text(input, defaults, "content"),
        Instant.parse(text(input, defaults, "occurredAt")));
  }

  private static JsonNode value(JsonNode input, JsonNode defaults, String field) {
    return input.has(field) ? input.get(field) : defaults.get(field);
  }

  private static String text(JsonNode input, JsonNode defaults, String field) {
    JsonNode value = value(input, defaults, field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static int integer(JsonNode input, JsonNode defaults, String field) {
    return value(input, defaults, field).asInt();
  }

  private static long longValue(JsonNode input, JsonNode defaults, String field) {
    return value(input, defaults, field).asLong();
  }

  private static boolean bool(JsonNode input, JsonNode defaults, String field) {
    return value(input, defaults, field).asBoolean();
  }

  private static List<String> strings(JsonNode values) {
    return java.util.stream.StreamSupport.stream(values.spliterator(), false)
        .map(JsonNode::asString)
        .toList();
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
