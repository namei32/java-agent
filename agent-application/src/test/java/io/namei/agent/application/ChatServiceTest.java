package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.InvalidModelResponseException;
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
import java.time.OffsetDateTime;
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
    assertThat(repository.appendSessionIds).containsExactly("demo");
    assertThat(repository.turns).hasSize(1);
    var turn = repository.turns.getFirst();
    assertThat(turn.user()).isEqualTo(new ChatMessage(MessageRole.USER, "第二问"));
    assertThat(turn.userAt()).isEqualTo(OffsetDateTime.parse("2026-07-13T00:00:00Z"));
    assertThat(turn.assistant()).isEqualTo(new ChatMessage(MessageRole.ASSISTANT, "第二答"));
    assertThat(turn.assistantAt()).isEqualTo(OffsetDateTime.parse("2026-07-13T00:00:00Z"));
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

  @Test
  void rejectsNullModelResponseWithoutPersisting() {
    var repository = new RecordingRepository(List.of());
    var model = new RecordingModel(null);

    assertThatThrownBy(() -> service(repository, model).chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(InvalidModelResponseException.class);
    assertThat(repository.turns).isEmpty();
  }

  @Test
  void rejectsBlankModelResponseWithoutPersisting() {
    var repository = new RecordingRepository(List.of());
    var model = new RecordingModel(new ChatModelResponse(" \n\t"));

    assertThatThrownBy(() -> service(repository, model).chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(InvalidModelResponseException.class);
    assertThat(repository.turns).isEmpty();
  }

  @Test
  void executesLoadModelAndAppendInsideSessionGate() {
    var invocations = new InvocationRecorder();
    var repository = new RecordingRepository(List.of(), invocations);
    var model = new RecordingModel(new ChatModelResponse("回答"), invocations);
    var gate = new RecordingGate(invocations);

    service(repository, model, gate).chat(new ChatCommand("demo", "问题"));

    assertThat(gate.sessionId).isEqualTo("demo");
    assertThat(invocations.entries)
        .containsExactly(
            new Invocation("load", true),
            new Invocation("model", true),
            new Invocation("append", true));
  }

  private ChatService service(RecordingRepository repository, ChatModelPort model) {
    SessionExecutionGate direct =
        new SessionExecutionGate() {
          @Override
          public <T> T execute(String sessionId, Supplier<T> action) {
            return action.get();
          }
        };
    return service(repository, model, direct);
  }

  private ChatService service(
      RecordingRepository repository, ChatModelPort model, SessionExecutionGate gate) {
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000),
        gate,
        "你是 Namei Agent。",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC));
  }

  private static final class RecordingModel implements ChatModelPort {
    private final ChatModelResponse response;
    private final InvocationRecorder invocations;
    private ChatModelRequest request;

    private RecordingModel(ChatModelResponse response) {
      this(response, new InvocationRecorder());
    }

    private RecordingModel(ChatModelResponse response, InvocationRecorder invocations) {
      this.response = response;
      this.invocations = invocations;
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      invocations.record("model");
      this.request = request;
      return response;
    }
  }

  private static final class RecordingRepository implements SessionRepository {
    private final List<ChatMessage> history;
    private final List<String> appendSessionIds = new ArrayList<>();
    private final List<PersistedTurn> turns = new ArrayList<>();
    private final InvocationRecorder invocations;

    private RecordingRepository(List<ChatMessage> history) {
      this(history, new InvocationRecorder());
    }

    private RecordingRepository(List<ChatMessage> history, InvocationRecorder invocations) {
      this.history = history;
      this.invocations = invocations;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      invocations.record("load");
      return new SessionSnapshot(sessionId, history, history.size());
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      invocations.record("append");
      appendSessionIds.add(sessionId);
      turns.add(turn);
    }
  }

  private static final class RecordingGate implements SessionExecutionGate {
    private final InvocationRecorder invocations;
    private String sessionId;

    private RecordingGate(InvocationRecorder invocations) {
      this.invocations = invocations;
    }

    @Override
    public <T> T execute(String sessionId, Supplier<T> action) {
      this.sessionId = sessionId;
      invocations.insideGateAction = true;
      try {
        return action.get();
      } finally {
        invocations.insideGateAction = false;
      }
    }
  }

  private static final class InvocationRecorder {
    private final List<Invocation> entries = new ArrayList<>();
    private boolean insideGateAction;

    private void record(String operation) {
      entries.add(new Invocation(operation, insideGateAction));
    }
  }

  private record Invocation(String operation, boolean insideGateAction) {}
}
