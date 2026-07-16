package io.namei.agent.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatProgressListener;
import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.TurnCancellation;
import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.ChatModelStreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingObservabilityDelegationTest {
  @Test
  void observedModelPortDelegatesStreamingObserverAndCancellation() {
    var delegate = new StreamingModel();
    var deltas = new ArrayList<String>();
    CancellationSignal cancellation = CancellationSignal.none();

    ChatModelResponse response =
        new ObservedChatModelPort(delegate, "provider-model")
            .generate(request(), deltas::add, cancellation);

    assertThat(deltas).containsExactly("流", "式");
    assertThat(response.content()).isEqualTo("流式");
    assertThat(delegate.cancellation).isSameAs(cancellation);
  }

  @Test
  void safeChatUseCaseDelegatesStreamingProgressAndCancellation() {
    var delegate = new StreamingChat();
    var deltas = new ArrayList<String>();
    TurnCancellation cancellation = TurnCancellation.none();
    var safe =
        new SafeChatUseCase(
            delegate, Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));

    ChatResult result = safe.chat(new ChatCommand("cli:demo", "问题"), cancellation, deltas::add);

    assertThat(deltas).containsExactly("答", "案");
    assertThat(result.assistant().content()).isEqualTo("答案");
    assertThat(delegate.cancellation).isSameAs(cancellation);
  }

  private static ChatModelRequest request() {
    return new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")));
  }

  private static final class StreamingModel implements ChatModelPort {
    private CancellationSignal cancellation;

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      throw new AssertionError("观察包装不能把流式请求降级为同步请求");
    }

    @Override
    public ChatModelResponse generate(
        ChatModelRequest request,
        ChatModelStreamObserver observer,
        CancellationSignal cancellation) {
      this.cancellation = cancellation;
      observer.onContentDelta("流");
      observer.onContentDelta("式");
      return new ChatModelResponse("流式");
    }
  }

  private static final class StreamingChat implements ChatUseCase {
    private TurnCancellation cancellation;

    @Override
    public ChatResult chat(ChatCommand command) {
      throw new AssertionError("安全包装不能把流式请求降级为同步请求");
    }

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      this.cancellation = cancellation;
      progressListener.onContentDelta("答");
      progressListener.onContentDelta("案");
      return new ChatResult(command.sessionId(), new ChatMessage(MessageRole.ASSISTANT, "答案"));
    }
  }
}
