package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageType;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.channel.TurnFailureCode;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MessageTurnStreamingTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void projectsFixtureDeltasInOrderBeforeOneAuthoritativeCompletion() throws Exception {
    JsonNode testCase = fixtureCase("multiple-deltas-before-authoritative-completion");
    var chat =
        StreamingChat.success(
            strings(testCase.path("input").path("deltas")),
            testCase.path("input").path("finalContent").asString());
    var sink = new RecordingSink();

    OutboundMessage terminal =
        new MessageTurnService(chat).process(inbound(), sink, TurnCancellation.none());

    assertThat(sink.messages)
        .extracting(message -> message.type().name())
        .containsExactlyElementsOf(strings(testCase.path("expected").path("eventTypes")));
    assertThat(sink.messages).extracting(OutboundMessage::sequence).containsExactly(0L, 1L, 2L, 3L);
    assertThat(sink.messages)
        .filteredOn(message -> message.type() == OutboundMessageType.CONTENT_DELTA)
        .extracting(OutboundMessage::content)
        .containsExactly("你", "好");
    assertThat(terminal).isSameAs(sink.messages.getLast());
    assertThat(terminal.content()).isEqualTo("你好");
  }

  @Test
  void preservesWhitespaceDeltaFromFixture() throws Exception {
    JsonNode testCase = fixtureCase("whitespace-delta-is-preserved");
    var sink = new RecordingSink();

    new MessageTurnService(
            StreamingChat.success(
                strings(testCase.path("input").path("deltas")),
                testCase.path("input").path("finalContent").asString()))
        .process(inbound(), sink, TurnCancellation.none());

    assertThat(sink.messages.get(1).type()).isEqualTo(OutboundMessageType.CONTENT_DELTA);
    assertThat(sink.messages.get(1).content())
        .isEqualTo(testCase.path("expected").path("publishedDeltas").get(0).asString());
  }

  @Test
  void treatsToolIterationPreviewAsTentativeAndCompletionAsAuthoritative() throws Exception {
    JsonNode testCase = fixtureCase("tool-iteration-preview-is-tentative");
    var sink = new RecordingSink();

    OutboundMessage terminal =
        new MessageTurnService(
                StreamingChat.success(
                    strings(testCase.path("input").path("deltas")),
                    testCase.path("input").path("finalContent").asString()))
            .process(inbound(), sink, TurnCancellation.none());

    assertThat(sink.messages)
        .filteredOn(message -> message.type() == OutboundMessageType.CONTENT_DELTA)
        .extracting(OutboundMessage::content)
        .containsExactly("查询中", "最终回答");
    assertThat(terminal.content())
        .isEqualTo(testCase.path("expected").path("authoritativeContent").asString());
  }

  @Test
  void publishesOneCancellationAfterVisibleDelta() throws Exception {
    JsonNode testCase = fixtureCase("cancelled-stream-does-not-commit");
    var cancellation = new TurnCancellationSource();
    var chat =
        StreamingChat.cancelling(strings(testCase.path("input").path("deltas")), cancellation);
    var sink = new RecordingSink();

    OutboundMessage terminal =
        new MessageTurnService(chat).process(inbound(), sink, cancellation.token());

    assertThat(sink.messages)
        .extracting(OutboundMessage::type)
        .containsExactly(
            OutboundMessageType.TURN_STARTED,
            OutboundMessageType.CONTENT_DELTA,
            OutboundMessageType.TURN_CANCELLED);
    assertThat(sink.messages).extracting(OutboundMessage::sequence).containsExactly(0L, 1L, 2L);
    assertThat(terminal.code()).isEqualTo(TurnCancellationCode.REQUESTED.name());
  }

  @Test
  void propagatesDeltaSinkFailureWithoutAttemptingTerminalPublication() {
    var chat = StreamingChat.success(List.of("一", "二"), "一二");
    var sink = new FailingSink(3, OutboundDeliveryException.Reason.CHANNEL_DISCONNECTED);

    assertThatThrownBy(
            () -> new MessageTurnService(chat).process(inbound(), sink, TurnCancellation.none()))
        .isInstanceOf(OutboundDeliveryException.class)
        .extracting(exception -> ((OutboundDeliveryException) exception).reason())
        .isEqualTo(OutboundDeliveryException.Reason.CHANNEL_DISCONNECTED);

    assertThat(sink.attempts).isEqualTo(3);
    assertThat(sink.delivered)
        .extracting(OutboundMessage::type)
        .containsExactly(OutboundMessageType.TURN_STARTED, OutboundMessageType.CONTENT_DELTA);
  }

  @Test
  void mapsStreamLimitAfterDeltaToOneStableFailureTerminal() {
    var chat =
        StreamingChat.failure(
            List.of("部分"), new ModelStreamLimitExceededException("provider-secret"));
    var sink = new RecordingSink();

    OutboundMessage terminal =
        new MessageTurnService(chat).process(inbound(), sink, TurnCancellation.none());

    assertThat(sink.messages)
        .extracting(OutboundMessage::type)
        .containsExactly(
            OutboundMessageType.TURN_STARTED,
            OutboundMessageType.CONTENT_DELTA,
            OutboundMessageType.TURN_FAILED);
    assertThat(terminal.code()).isEqualTo(TurnFailureCode.TURN_LIMIT_EXCEEDED.name());
    assertThat(terminal.content()).isEmpty();
    assertThat(terminal.toString()).doesNotContain("provider-secret");
  }

  private static JsonNode fixtureCase(String id) throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("message-bus/provider-streaming-cli.json"));
    for (JsonNode testCase : fixture.path("applicationCases")) {
      if (id.equals(testCase.path("id").asString())) {
        return testCase;
      }
    }
    throw new AssertionError("Fixture Case 不存在: " + id);
  }

  private static List<String> strings(JsonNode values) {
    var result = new ArrayList<String>();
    values.forEach(value -> result.add(value.asString()));
    return List.copyOf(result);
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private static InboundMessage inbound() {
    return new InboundMessage(
        1,
        "message-1",
        "turn-1",
        "cli:demo",
        new MessageRoute("cli", "demo"),
        "local-user",
        "问题",
        Instant.parse("2026-07-15T00:00:00Z"));
  }

  private static final class StreamingChat implements ChatUseCase {
    private final List<String> deltas;
    private final ChatResult result;
    private final RuntimeException failure;
    private final TurnCancellationSource cancellationSource;

    private StreamingChat(
        List<String> deltas,
        ChatResult result,
        RuntimeException failure,
        TurnCancellationSource cancellationSource) {
      this.deltas = List.copyOf(deltas);
      this.result = result;
      this.failure = failure;
      this.cancellationSource = cancellationSource;
    }

    static StreamingChat success(List<String> deltas, String answer) {
      return new StreamingChat(deltas, result(answer), null, null);
    }

    static StreamingChat failure(List<String> deltas, RuntimeException failure) {
      return new StreamingChat(deltas, null, failure, null);
    }

    static StreamingChat cancelling(
        List<String> deltas, TurnCancellationSource cancellationSource) {
      return new StreamingChat(deltas, null, null, cancellationSource);
    }

    @Override
    public ChatResult chat(ChatCommand command) {
      throw new AssertionError("Message Turn 必须调用流式 Chat 入口");
    }

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      for (String delta : deltas) {
        progressListener.onContentDelta(delta);
      }
      if (cancellationSource != null) {
        cancellationSource.cancel();
        cancellation.throwIfCancellationRequested();
      }
      if (failure != null) {
        throw failure;
      }
      return result;
    }

    private static ChatResult result(String answer) {
      return new ChatResult("cli:demo", new ChatMessage(MessageRole.ASSISTANT, answer));
    }
  }

  private static final class RecordingSink implements OutboundMessageSink {
    private final List<OutboundMessage> messages = new ArrayList<>();

    @Override
    public void publish(OutboundMessage message) {
      messages.add(message);
    }
  }

  private static final class FailingSink implements OutboundMessageSink {
    private final int failAt;
    private final OutboundDeliveryException.Reason reason;
    private final List<OutboundMessage> delivered = new ArrayList<>();
    private int attempts;

    private FailingSink(int failAt, OutboundDeliveryException.Reason reason) {
      this.failAt = failAt;
      this.reason = reason;
    }

    @Override
    public void publish(OutboundMessage message) {
      attempts++;
      if (attempts == failAt) {
        throw new OutboundDeliveryException(reason);
      }
      delivered.add(message);
    }
  }
}
