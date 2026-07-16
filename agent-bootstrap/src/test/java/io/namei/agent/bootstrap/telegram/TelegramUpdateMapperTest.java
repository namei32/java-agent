package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.channel.InboundMessage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TelegramUpdateMapperTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> matchesApprovedTelegramMapperFixture() throws Exception {
    JsonNode fixture = fixture();
    var allowed = new HashSet<Long>();
    fixture.path("defaults").path("allowedUserIds").forEach(id -> allowed.add(id.asLong()));
    var mapper = new TelegramUpdateMapper(allowed, () -> "turn-fixture");
    var tests = new ArrayList<DynamicTest>();

    for (JsonNode testCase : fixture.path("cases")) {
      tests.add(
          DynamicTest.dynamicTest(
              testCase.path("id").asString(),
              () ->
                  verify(
                      mapper.map(update(fixture, testCase.path("input"))),
                      testCase.path("expected"))));
    }
    return tests.stream();
  }

  @Test
  void projectValuesAndDecisionsDoNotRenderExternalIdsOrContent() {
    var message =
        new TelegramMessage(
            42,
            Instant.parse("2026-07-16T00:00:00Z"),
            10001,
            "private",
            10001,
            false,
            "secret-content");
    var update = new TelegramUpdate(500, message);
    TelegramInboundDecision decision =
        new TelegramUpdateMapper(new HashSet<>(java.util.Set.of(10001L)), () -> "turn-secret")
            .map(update);

    assertThat(message.toString()).doesNotContain("42", "10001", "secret-content");
    assertThat(update.toString()).doesNotContain("500", "42", "10001", "secret-content");
    assertThat(decision.toString())
        .doesNotContain("500", "42", "10001", "secret-content", "turn-secret");
  }

  @Test
  void secureIdsUseIndependent128BitHexValues() {
    var ids = new SecureTelegramIdGenerator();

    String first = ids.newTurnId();
    String second = ids.newTurnId();

    assertThat(first).matches("turn-[0-9a-f]{32}");
    assertThat(second).matches("turn-[0-9a-f]{32}").isNotEqualTo(first);
  }

  private static void verify(TelegramInboundDecision actual, JsonNode expected) {
    assertThat(actual.kind().name()).isEqualTo(expected.path("kind").asString());
    switch (actual.kind()) {
      case ACCEPTED -> verifyInbound(actual.inbound(), expected);
      case CONTROL ->
          assertThat(actual.control().name()).isEqualTo(expected.path("control").asString());
      case IGNORED ->
          assertThat(actual.reason().name()).isEqualTo(expected.path("reason").asString());
    }
  }

  private static void verifyInbound(InboundMessage actual, JsonNode expected) {
    assertThat(actual.messageId()).isEqualTo(expected.path("messageId").asString());
    assertThat(actual.turnId()).isEqualTo(expected.path("turnId").asString());
    assertThat(actual.sessionId()).isEqualTo(expected.path("sessionId").asString());
    assertThat(actual.route().channel()).isEqualTo(expected.path("channel").asString());
    assertThat(actual.route().conversationId())
        .isEqualTo(expected.path("conversationId").asString());
    assertThat(actual.senderId()).isEqualTo(expected.path("senderId").asString());
    assertThat(actual.content()).isEqualTo(expected.path("content").asString());
    assertThat(actual.occurredAt())
        .isEqualTo(Instant.parse(expected.path("occurredAt").asString()));
  }

  private static TelegramUpdate update(JsonNode fixture, JsonNode input) {
    JsonNode defaults = fixture.path("defaults").path("update");
    long updateId = longValue(input, defaults, "updateId");
    if (input.has("message") && input.path("message").isNull()) {
      return new TelegramUpdate(updateId, null);
    }
    String text;
    if (input.has("repeatText")) {
      text = input.path("repeatText").asString().repeat(input.path("repeatCount").asInt());
    } else if (input.has("text") && input.path("text").isNull()) {
      text = null;
    } else {
      text = textValue(input, defaults, "text");
    }
    Instant occurredAt =
        input.has("occurredAt") && input.path("occurredAt").isNull()
            ? null
            : Instant.parse(textValue(input, defaults, "occurredAt"));
    return new TelegramUpdate(
        updateId,
        new TelegramMessage(
            longValue(input, defaults, "messageId"),
            occurredAt,
            longValue(input, defaults, "chatId"),
            textValue(input, defaults, "chatType"),
            longValue(input, defaults, "senderId"),
            booleanValue(input, defaults, "senderBot"),
            text));
  }

  private static String textValue(JsonNode input, JsonNode defaults, String field) {
    return (input.has(field) ? input : defaults).path(field).asString();
  }

  private static long longValue(JsonNode input, JsonNode defaults, String field) {
    return (input.has(field) ? input : defaults).path(field).asLong();
  }

  private static boolean booleanValue(JsonNode input, JsonNode defaults, String field) {
    return (input.has(field) ? input : defaults).path(field).asBoolean();
  }

  private static JsonNode fixture() throws Exception {
    return JSON.readTree(goldenRoot().resolve("channels/telegram-channel-v1.json"));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
