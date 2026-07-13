package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ChatServiceTest {
  @Test
  void buildsPromptAndPersistsOneCompleteTurn() {
    var repository =
        new RecordingRepository(
            List.of(
                new ChatMessage(MessageRole.USER, "第一问"),
                new ChatMessage(MessageRole.ASSISTANT, "第一答")));
    var model = new RecordingModel(new ChatModelResponse("第二答"));
    var service = service(repository, model);

    ChatResult result = service.chat(new ChatCommand("demo", "第二问"));

    assertThat(result.assistant().content()).isEqualTo("第二答");
    assertThat(model.request.messages())
        .containsExactly(
            new ChatMessage(MessageRole.SYSTEM, "你是 Namei Agent。"),
            new ChatMessage(MessageRole.USER, "第一问"),
            new ChatMessage(MessageRole.ASSISTANT, "第一答"),
            new ChatMessage(MessageRole.USER, "第二问"));
    assertThat(repository.turns).hasSize(1);
  }

  @Test
  void doesNotPersistWhenModelFails() {
    var repository = new RecordingRepository(List.of());
    ChatModelPort model =
        request -> {
          throw new IllegalStateException("upstream");
        };

    assertThatThrownBy(() -> service(repository, model).chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.turns).isEmpty();
  }

  private ChatService service(RecordingRepository repository, ChatModelPort model) {
    SessionExecutionGate direct =
        new SessionExecutionGate() {
          @Override
          public <T> T execute(String sessionId, Supplier<T> action) {
            return action.get();
          }
        };
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000),
        direct,
        "你是 Namei Agent。",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC));
  }

  private static final class RecordingModel implements ChatModelPort {
    private final ChatModelResponse response;
    private ChatModelRequest request;

    private RecordingModel(ChatModelResponse response) {
      this.response = response;
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      this.request = request;
      return response;
    }
  }

  private static final class RecordingRepository implements SessionRepository {
    private final List<ChatMessage> history;
    private final List<PersistedTurn> turns = new ArrayList<>();

    private RecordingRepository(List<ChatMessage> history) {
      this.history = history;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, history, history.size());
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      turns.add(turn);
    }
  }
}
