package io.namei.agent.bootstrap.channel.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.namei.agent.bootstrap.telegram.TelegramBotToken;
import io.namei.agent.bootstrap.telegram.TelegramChannelInstance;
import io.namei.agent.bootstrap.telegram.TelegramTerminalRenderer;
import io.namei.agent.bootstrap.telegram.TelegramTextChunker;
import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.reliability.ChannelFingerprint;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelReliabilityRules;
import io.namei.agent.kernel.channel.reliability.DeliveryAttemptOutcome;
import io.namei.agent.kernel.channel.reliability.DeliveryMessageType;
import io.namei.agent.kernel.channel.reliability.DeliveryPartState;
import io.namei.agent.kernel.channel.reliability.DeliverySourceKind;
import io.namei.agent.kernel.channel.reliability.DeliveryState;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import io.namei.agent.kernel.channel.reliability.TurnClaimState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ChannelReliableDeliveryGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> everyApprovedCaseStillProjectsThroughProductionFactories() throws Exception {
    JsonNode fixture = fixture();
    var caseIds = new HashSet<String>();
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode testCase : fixture.path("cases")) {
      String caseId = testCase.path("id").asString();
      assertThat(caseIds.add(caseId)).as("重复 Case ID: %s", caseId).isTrue();
      tests.add(
          DynamicTest.dynamicTest(caseId, () -> verifyCase(testCase, fixture.path("defaults"))));
    }
    assertThat(tests).hasSize(40);
    return tests.stream();
  }

  @Test
  void fixtureBindsTheTelegramInstanceAndTerminalRendererAcrossLayers() throws Exception {
    JsonNode fixture = fixture();
    JsonNode defaults = fixture.path("defaults");
    String botId = defaults.path("trustedInstanceKey").asString();
    String expectedInstance =
        fixture.path("cases").get(0).path("expected").path("value").asString();
    var token = new TelegramBotToken(botId + ":FIXTURE_FAKE_TOKEN_1234567890");

    assertThat(TelegramChannelInstance.from(token).id().value()).isEqualTo(expectedInstance);
    assertThat(
            new TelegramTerminalRenderer(new TelegramTextChunker())
                .project(
                    OutboundMessage.completed(
                        defaults.path("turnId").asString(),
                        defaults.path("sessionId").asString(),
                        new MessageRoute(
                            defaults.path("channel").asString(),
                            defaults.path("conversationId").asString()),
                        1,
                        defaults.path("parts").get(0).asString())))
        .containsExactlyElementsOf(strings(defaults.path("parts")));
    assertThat(token.toString()).doesNotContain(botId, "FIXTURE_FAKE_TOKEN");
  }

  @Test
  void fixtureRemainsJavaOwnedAndPointsToTheApprovedEvidence() throws Exception {
    JsonNode fixture = fixture();

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("channel-reliable-delivery-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("contractEvidence").path("approvedOn").asString())
        .isEqualTo("2026-07-16");
    assertThat(fixture.path("pythonEvidence").isMissingNode()).isTrue();
  }

  private static void verifyCase(JsonNode testCase, JsonNode defaults) {
    JsonNode expected = testCase.path("expected");
    String caseId = testCase.path("id").asString();
    try {
      Object actual = evaluate(testCase, defaults);
      if (expected.has("accepted") && !expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + caseId);
      }
      if (expected.has("value")) {
        assertThat(actual).as(caseId).isEqualTo(expected.path("value").asString());
      } else {
        assertThat(actual).as(caseId).isEqualTo(expected.path("allowed").asBoolean());
      }
    } catch (IllegalArgumentException failure) {
      if (!expected.has("accepted") || expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 不应被拒绝: " + caseId, failure);
      }
    }
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

  private static JsonNode fixture() throws Exception {
    return JSON.readTree(goldenRoot().resolve("channels/channel-reliable-delivery-v1.json"));
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
