package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.namei.agent.application.BoundedOutboundBuffer;
import io.namei.agent.application.OutboundDeliveryException;
import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessageSequence;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.channel.TurnFailureCode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class TelegramGoldenFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> productionComponentsConsumeEveryApprovedCase() throws Exception {
    JsonNode fixture = fixture();
    var allowed = new HashSet<Long>();
    fixture.path("defaults").path("allowedUserIds").forEach(id -> allowed.add(id.asLong()));

    var tests = new ArrayList<DynamicTest>();
    for (JsonNode testCase : fixture.path("cases")) {
      tests.add(
          DynamicTest.dynamicTest(
              testCase.path("id").asString(), () -> consume(testCase, fixture, allowed)));
    }
    return tests.stream();
  }

  @Test
  void fixtureDeclaresEveryProductionConsumerAndApprovedEvidence() throws Exception {
    JsonNode fixture = fixture();

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("contractEvidence").path("approvedOn").asString())
        .isEqualTo("2026-07-16");
    assertThat(fixture.path("cases"))
        .extracting(testCase -> testCase.path("component").asString("mapper"))
        .contains("mapper", "chunker", "renderer", "lifecycle", "configuration");
  }

  private static void consume(JsonNode testCase, JsonNode fixture, HashSet<Long> allowed) {
    switch (testCase.path("component").asString("mapper")) {
      case "mapper" -> verifyMapper(testCase, fixture, allowed);
      case "chunker" -> verifyChunker(testCase);
      case "renderer" -> verifyRenderer(testCase);
      case "lifecycle" -> verifyLifecycle(testCase);
      case "configuration" -> verifyConfiguration(testCase);
      default -> throw new AssertionError("未知 Telegram Fixture component");
    }
  }

  private static void verifyMapper(JsonNode testCase, JsonNode fixture, HashSet<Long> allowed) {
    TelegramInboundDecision actual =
        new TelegramUpdateMapper(allowed, () -> "turn-fixture")
            .map(update(fixture, testCase.path("input")));
    JsonNode expected = testCase.path("expected");

    assertThat(actual.kind().name()).isEqualTo(expected.path("kind").asString());
    switch (actual.kind()) {
      case ACCEPTED -> {
        InboundMessage inbound = actual.inbound();
        assertThat(inbound.messageId()).isEqualTo(expected.path("messageId").asString());
        assertThat(inbound.turnId()).isEqualTo(expected.path("turnId").asString());
        assertThat(inbound.sessionId()).isEqualTo(expected.path("sessionId").asString());
        assertThat(inbound.route().channel()).isEqualTo(expected.path("channel").asString());
        assertThat(inbound.route().conversationId())
            .isEqualTo(expected.path("conversationId").asString());
        assertThat(inbound.senderId()).isEqualTo(expected.path("senderId").asString());
        assertThat(inbound.content()).isEqualTo(expected.path("content").asString());
        assertThat(inbound.occurredAt())
            .isEqualTo(Instant.parse(expected.path("occurredAt").asString()));
      }
      case CONTROL ->
          assertThat(actual.control().name()).isEqualTo(expected.path("control").asString());
      case IGNORED ->
          assertThat(actual.reason().name()).isEqualTo(expected.path("reason").asString());
    }
  }

  private static void verifyChunker(JsonNode testCase) {
    JsonNode input = testCase.path("input");
    String text;
    if (input.has("repeatText")) {
      text = input.path("repeatText").asString().repeat(input.path("repeatCount").asInt());
    } else {
      text =
          input.path("prefixText").asString().repeat(input.path("prefixCount").asInt())
              + input.path("emoji").asString()
              + input.path("suffix").asString();
    }

    List<String> chunks = new TelegramTextChunker().split(text);
    JsonNode expected = testCase.path("expected");
    assertThat(chunks)
        .extracting(String::length)
        .containsExactlyElementsOf(ints(expected.path("chunkUtf16Lengths")));
    assertThat(String.join("", chunks)).isEqualTo(text);
    assertThat(expected.path("lossless").asBoolean()).isTrue();
    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.length()).isLessThanOrEqualTo(TelegramTextChunker.MAX_CHUNK_UNITS);
              assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
            });
  }

  private static void verifyRenderer(JsonNode testCase) {
    var api = new RecordingApi();
    var inbound = inbound();
    var renderer =
        new TelegramTerminalRenderer(
            inbound,
            10001,
            new TelegramTextChunker(),
            new TelegramDeliveryPolicy(api, duration -> {}, Duration.ofSeconds(5)));
    var sequence = new OutboundMessageSequence(inbound);

    for (JsonNode event : testCase.path("input").path("events")) {
      switch (event.path("type").asString()) {
        case "TURN_STARTED" -> renderer.accept(sequence.started());
        case "CONTENT_DELTA" -> renderer.accept(sequence.delta(event.path("content").asString()));
        case "TURN_COMPLETED" ->
            renderer.accept(sequence.completed(event.path("content").asString()));
        case "TURN_CANCELLED" ->
            renderer.accept(
                sequence.cancelled(TurnCancellationCode.valueOf(event.path("code").asString())));
        case "TURN_FAILED" ->
            renderer.accept(
                sequence.failed(TurnFailureCode.valueOf(event.path("code").asString())));
        default -> throw new AssertionError("未知 Telegram Fixture event");
      }
    }

    JsonNode expected = testCase.path("expected");
    assertThat(api.texts()).containsExactlyElementsOf(texts(expected.path("deliveries")));
    assertThat(renderer.isTerminal()).isEqualTo(expected.path("terminal").asBoolean());
  }

  private static void verifyLifecycle(JsonNode testCase) {
    var inbound = inbound();
    var sequence = new OutboundMessageSequence(inbound);
    var buffer = new BoundedOutboundBuffer(inbound, 4, Duration.ofSeconds(1));
    OutboundDeliveryException failure = null;

    for (JsonNode action : testCase.path("input").path("actions")) {
      switch (action.asString()) {
        case "REQUEST_CANCEL" -> buffer.requestCancellation();
        case "DISCONNECT" -> buffer.disconnect();
        case "SHUTDOWN" -> buffer.shutdown();
        case "PUBLISH_STARTED" -> buffer.publish(sequence.started());
        case "PUBLISH_COMPLETED" -> {
          if (sequence.isTerminal()) {
            failure =
                catchThrowableOfType(
                    () ->
                        buffer.publish(
                            io.namei.agent.kernel.channel.OutboundMessage.completed(
                                inbound.turnId(), inbound.sessionId(), inbound.route(), 2, "迟到终态")),
                    OutboundDeliveryException.class);
          } else {
            buffer.publish(sequence.completed("权威最终回答"));
          }
        }
        default -> throw new AssertionError("未知 Telegram Fixture lifecycle action");
      }
    }

    JsonNode expected = testCase.path("expected");
    assertThat(buffer.size()).isEqualTo(expected.path("bufferSize").asInt());
    if (expected.has("cancellationCode")) {
      assertThat(buffer.cancellation().reason().name())
          .isEqualTo(expected.path("cancellationCode").asString());
    }
    if (expected.has("deliveryReason")) {
      assertThat(failure).isNotNull();
      assertThat(failure.reason().name()).isEqualTo(expected.path("deliveryReason").asString());
    }
  }

  private static void verifyConfiguration(JsonNode testCase) {
    TelegramProperties properties =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("agent.channels.telegram", Bindable.of(TelegramProperties.class));

    assertThat(properties.enabled())
        .isEqualTo(testCase.path("expected").path("enabled").asBoolean());
    assertThat(properties.allowedUserIds())
        .containsExactlyElementsOf(longs(testCase.path("expected").path("allowedUserIds")));
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

  private static InboundMessage inbound() {
    return new InboundMessage(
        1,
        "telegram:10001:42",
        "turn-fixture",
        "telegram:10001",
        new MessageRoute("telegram", "10001"),
        "10001",
        "问题",
        Instant.parse("2026-07-16T00:00:00Z"));
  }

  private static List<Integer> ints(JsonNode values) {
    var result = new ArrayList<Integer>();
    values.forEach(value -> result.add(value.asInt()));
    return List.copyOf(result);
  }

  private static List<Long> longs(JsonNode values) {
    var result = new ArrayList<Long>();
    values.forEach(value -> result.add(value.asLong()));
    return List.copyOf(result);
  }

  private static List<String> texts(JsonNode values) {
    var result = new ArrayList<String>();
    values.forEach(value -> result.add(value.asString()));
    return List.copyOf(result);
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

  private record Send(long chatId, String text) {}

  private static final class RecordingApi implements TelegramBotApi {
    private final List<Send> sends = new ArrayList<>();

    @Override
    public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
      throw new UnsupportedOperationException("Fixture 不使用 Poll");
    }

    @Override
    public void sendMessage(long chatId, String text) {
      sends.add(new Send(chatId, text));
    }

    private List<String> texts() {
      return sends.stream().map(Send::text).toList();
    }
  }
}
