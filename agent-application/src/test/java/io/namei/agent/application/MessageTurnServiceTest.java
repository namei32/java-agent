package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageType;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.channel.TurnFailureCode;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.error.SessionPersistenceException;
import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class MessageTurnServiceTest {
  @Test
  void projectsSuccessfulChatIntoStartedAndOneAuthoritativeCompletion() {
    var chat = RecordingChat.success("完整回答");
    var sink = new RecordingSink();
    var cancellation = new TurnCancellationSource();

    OutboundMessage terminal =
        new MessageTurnService(chat).process(inbound(), sink, cancellation.token());

    assertThat(chat.calls).isEqualTo(1);
    assertThat(chat.command).isEqualTo(new ChatCommand("cli:demo", "问题"));
    assertThat(chat.cancellation).isSameAs(cancellation.token());
    assertThat(sink.messages)
        .extracting(OutboundMessage::type)
        .containsExactly(OutboundMessageType.TURN_STARTED, OutboundMessageType.TURN_COMPLETED);
    assertThat(sink.messages).extracting(OutboundMessage::sequence).containsExactly(0L, 1L);
    assertThat(terminal).isSameAs(sink.messages.getLast());
    assertThat(terminal.content()).isEqualTo("完整回答");
  }

  @ParameterizedTest
  @EnumSource(TurnCancellationCode.class)
  void projectsEveryPreexistingCancellationReasonWithoutCallingChat(TurnCancellationCode reason) {
    var chat = RecordingChat.success("不应执行");
    var sink = new RecordingSink();
    var cancellation = new TurnCancellationSource();
    cancellation.cancel(reason);

    OutboundMessage terminal =
        new MessageTurnService(chat).process(inbound(), sink, cancellation.token());

    assertThat(chat.calls).isZero();
    assertThat(sink.messages)
        .extracting(OutboundMessage::type)
        .containsExactly(OutboundMessageType.TURN_STARTED, OutboundMessageType.TURN_CANCELLED);
    assertThat(terminal.code()).isEqualTo(reason.name());
    assertThat(terminal.retryable()).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("failureCases")
  void mapsRuntimeFailuresToOneSafeTerminal(
      String name, RuntimeException failure, TurnFailureCode expected) {
    var chat = RecordingChat.failure(failure);
    var sink = new RecordingSink();

    OutboundMessage terminal =
        new MessageTurnService(chat).process(inbound(), sink, TurnCancellation.none());

    assertThat(sink.messages)
        .extracting(OutboundMessage::type)
        .containsExactly(OutboundMessageType.TURN_STARTED, OutboundMessageType.TURN_FAILED);
    assertThat(terminal.code()).isEqualTo(expected.name());
    assertThat(terminal.retryable()).isEqualTo(expected.retryable());
    assertThat(terminal.content()).isEmpty();
    assertThat(terminal.toString())
        .as(name)
        .doesNotContain("Bearer", "secret-session", "secret-database", "upstream-secret");
  }

  @Test
  void propagatesTerminalSinkFailureWithoutAttemptingAnotherTerminal() {
    var chat = RecordingChat.success("完整回答");
    var sink = new FailingSink(2, OutboundDeliveryException.Reason.BACKPRESSURE_EXCEEDED);

    assertThatThrownBy(
            () -> new MessageTurnService(chat).process(inbound(), sink, TurnCancellation.none()))
        .isInstanceOf(OutboundDeliveryException.class)
        .extracting(exception -> ((OutboundDeliveryException) exception).reason())
        .isEqualTo(OutboundDeliveryException.Reason.BACKPRESSURE_EXCEEDED);

    assertThat(sink.attempts).isEqualTo(2);
    assertThat(chat.calls).isEqualTo(1);
  }

  @Test
  void doesNotStartBusinessWorkWhenInitialSinkPublicationFails() {
    var chat = RecordingChat.success("不应执行");
    var sink = new FailingSink(1, OutboundDeliveryException.Reason.CHANNEL_DISCONNECTED);

    assertThatThrownBy(
            () -> new MessageTurnService(chat).process(inbound(), sink, TurnCancellation.none()))
        .isInstanceOf(OutboundDeliveryException.class);

    assertThat(sink.attempts).isEqualTo(1);
    assertThat(chat.calls).isZero();
  }

  private static Stream<Arguments> failureCases() {
    return Stream.of(
        Arguments.of(
            "session busy",
            new SessionLockTimeoutException("secret-session"),
            TurnFailureCode.SESSION_BUSY),
        Arguments.of(
            "model timeout",
            new ModelTimeoutException("Bearer upstream-secret", new RuntimeException()),
            TurnFailureCode.MODEL_TIMEOUT),
        Arguments.of(
            "model unavailable",
            new ModelInvocationException("Bearer upstream-secret", new RuntimeException()),
            TurnFailureCode.MODEL_UNAVAILABLE),
        Arguments.of(
            "invalid model",
            new InvalidModelResponseException("Bearer upstream-secret"),
            TurnFailureCode.INVALID_MODEL_RESPONSE),
        Arguments.of(
            "call limit",
            new ToolCallLimitExceededException("Bearer upstream-secret"),
            TurnFailureCode.TURN_LIMIT_EXCEEDED),
        Arguments.of(
            "loop limit",
            new ToolLoopLimitExceededException("Bearer upstream-secret"),
            TurnFailureCode.TURN_LIMIT_EXCEEDED),
        Arguments.of(
            "context",
            new MemoryContextUnavailableException(),
            TurnFailureCode.CONTEXT_UNAVAILABLE),
        Arguments.of(
            "approval", new ApprovalUnavailableException(), TurnFailureCode.APPROVAL_UNAVAILABLE),
        Arguments.of(
            "side effect unknown",
            new SideEffectStateUnknownException(),
            TurnFailureCode.SIDE_EFFECT_STATE_UNKNOWN),
        Arguments.of(
            "persistence",
            new SessionPersistenceException("secret-database"),
            TurnFailureCode.PERSISTENCE_FAILED),
        Arguments.of(
            "unknown",
            new IllegalStateException("Bearer upstream-secret"),
            TurnFailureCode.INTERNAL_ERROR));
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

  private static final class RecordingChat implements ChatUseCase {
    private final ChatResult result;
    private final RuntimeException failure;
    private int calls;
    private ChatCommand command;
    private TurnCancellation cancellation;

    private RecordingChat(ChatResult result, RuntimeException failure) {
      this.result = result;
      this.failure = failure;
    }

    static RecordingChat success(String answer) {
      return new RecordingChat(
          new ChatResult("cli:demo", new ChatMessage(MessageRole.ASSISTANT, answer)), null);
    }

    static RecordingChat failure(RuntimeException failure) {
      return new RecordingChat(null, failure);
    }

    @Override
    public ChatResult chat(ChatCommand command) {
      return chat(command, TurnCancellation.none());
    }

    @Override
    public ChatResult chat(ChatCommand command, TurnCancellation cancellation) {
      calls++;
      this.command = command;
      this.cancellation = cancellation;
      if (failure != null) {
        throw failure;
      }
      return result;
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
    }
  }
}
