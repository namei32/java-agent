package io.namei.agent.kernel.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MessageContractGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void enforcesEveryVersionedMessageFixtureCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("message-bus/versioned-channel-message.json"));
    JsonNode cases = fixture.path("cases");

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(cases).hasSize(40);

    for (JsonNode testCase : cases) {
      String caseId = testCase.path("id").asString();
      JsonNode input = testCase.path("input");
      JsonNode defaults = fixture.path("defaults").path(input.path("kind").asString());
      boolean accepted = testCase.path("expected").path("accepted").asBoolean();

      try {
        Object actual = evaluate(input, defaults);
        if (!accepted) {
          fail("Case 应被拒绝: " + caseId);
        }
        assertProjection(caseId, actual, testCase.path("expected"));
      } catch (IllegalArgumentException exception) {
        if (accepted) {
          throw new AssertionError("Case 应被接受: " + caseId, exception);
        }
      }
    }
  }

  @Test
  void redactsMessageContentAndRoutingFromSafeStrings() {
    var route = new MessageRoute("private", "conversation-secret");
    var inbound =
        new InboundMessage(
            1,
            "message-secret",
            "turn-secret",
            "session-secret",
            route,
            "sender-secret",
            "content-secret",
            Instant.parse("2026-07-15T00:00:00Z"));
    var outbound = OutboundMessage.completed("turn-secret", "session-secret", route, 1, "reply-secret");

    assertThat(route.toString()).doesNotContain("private", "conversation-secret");
    assertThat(inbound.toString())
        .doesNotContain("message-secret", "turn-secret", "session-secret", "sender-secret", "content-secret");
    assertThat(outbound.toString())
        .doesNotContain("turn-secret", "session-secret", "private", "conversation-secret", "reply-secret");
  }

  private static Object evaluate(JsonNode input, JsonNode defaults) {
    return switch (input.path("kind").asString()) {
      case "inbound" -> inbound(input, defaults);
      case "outbound" -> outbound(input, defaults);
      default -> throw new IllegalArgumentException("未知 Fixture Kind");
    };
  }

  private static InboundMessage inbound(JsonNode input, JsonNode defaults) {
    JsonNode occurredAt = value(input, defaults, "occurredAt");
    return new InboundMessage(
        integer(input, defaults, "schemaVersion"),
        text(input, defaults, "messageId"),
        text(input, defaults, "turnId"),
        text(input, defaults, "sessionId"),
        new MessageRoute(
            text(input, defaults, "channel"), text(input, defaults, "conversationId")),
        text(input, defaults, "senderId"),
        text(input, defaults, "content"),
        occurredAt.isNull() ? null : Instant.parse(occurredAt.asString()));
  }

  private static OutboundMessage outbound(JsonNode input, JsonNode defaults) {
    String type = text(input, defaults, "type");
    return new OutboundMessage(
        integer(input, defaults, "schemaVersion"),
        text(input, defaults, "turnId"),
        text(input, defaults, "sessionId"),
        new MessageRoute(
            text(input, defaults, "channel"), text(input, defaults, "conversationId")),
        longValue(input, defaults, "sequence"),
        OutboundMessageType.valueOf(type),
        text(input, defaults, "content"),
        text(input, defaults, "code"),
        bool(input, defaults, "retryable"));
  }

  private static void assertProjection(String caseId, Object actual, JsonNode expected) {
    if (actual instanceof InboundMessage inbound) {
      assertThat(inbound.messageId()).as(caseId).isEqualTo(expected.path("messageId").asString());
      assertThat(inbound.turnId()).as(caseId).isEqualTo(expected.path("turnId").asString());
      assertThat(inbound.sessionId()).as(caseId).isEqualTo(expected.path("sessionId").asString());
      assertThat(inbound.route().channel()).as(caseId).isEqualTo(expected.path("channel").asString());
      assertThat(inbound.route().conversationId())
          .as(caseId)
          .isEqualTo(expected.path("conversationId").asString());
      assertThat(inbound.senderId()).as(caseId).isEqualTo(expected.path("senderId").asString());
      assertThat(inbound.content()).as(caseId).isEqualTo(expected.path("content").asString());
      assertThat(inbound.occurredAt().toString())
          .as(caseId)
          .isEqualTo(expected.path("occurredAt").asString());
      return;
    }

    OutboundMessage outbound = (OutboundMessage) actual;
    assertThat(outbound.sequence()).as(caseId).isEqualTo(expected.path("sequence").asLong());
    assertThat(outbound.type().name()).as(caseId).isEqualTo(expected.path("type").asString());
    assertThat(outbound.content()).as(caseId).isEqualTo(expected.path("content").asString());
    assertThat(outbound.code()).as(caseId).isEqualTo(expected.path("code").asString());
    assertThat(outbound.retryable()).as(caseId).isEqualTo(expected.path("retryable").asBoolean());
  }

  private static JsonNode value(JsonNode input, JsonNode defaults, String field) {
    if (field.equals(input.path("repeatField").asString())) {
      return JSON.getNodeFactory()
          .textNode(
              input.path("repeatValue").asString().repeat(input.path("repeatCount").asInt()));
    }
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

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
