package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class PythonPromptGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> matchesPythonMessageEnvelopeProjection() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("prompt/message-envelope.json"));
    var tests = new ArrayList<DynamicTest>();

    for (JsonNode testCase : fixture.path("cases")) {
      tests.add(
          DynamicTest.dynamicTest(testCase.path("id").asString(), () -> verifyCase(testCase)));
    }
    return tests.stream();
  }

  private static void verifyCase(JsonNode testCase) {
    JsonNode input = testCase.path("input");
    var repository = new GoldenRepository(messages(input.path("history")));
    var model = new CapturingModel();
    var service =
        new ChatService(
            repository,
            model,
            new ConversationHistorySelector(),
            new HistoryLimits(40, 100_000),
            directGate(),
            input.path("systemPrompt").asString(),
            Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC));

    service.chat(new ChatCommand("golden", input.path("currentMessage").asString()));

    assertThat(model.request.messages())
        .containsExactlyElementsOf(messages(testCase.path("expected").path("messages")));
    assertThat(repository.appended).hasSize(1);
  }

  private static SessionExecutionGate directGate() {
    return new SessionExecutionGate() {
      @Override
      public <T> T execute(String sessionId, Supplier<T> action) {
        return action.get();
      }
    };
  }

  private static List<ChatMessage> messages(JsonNode nodes) {
    var messages = new ArrayList<ChatMessage>();
    for (JsonNode node : nodes) {
      messages.add(
          new ChatMessage(
              MessageRole.valueOf(node.path("role").asString().toUpperCase(Locale.ROOT)),
              node.path("content").asString()));
    }
    return List.copyOf(messages);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private static final class CapturingModel implements ChatModelPort {
    private ChatModelRequest request;

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      this.request = request;
      return new ChatModelResponse("固定回答");
    }
  }

  private static final class GoldenRepository implements SessionRepository {
    private final List<ChatMessage> history;
    private final List<PersistedTurn> appended = new ArrayList<>();

    private GoldenRepository(List<ChatMessage> history) {
      this.history = history;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, history, history.size());
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      appended.add(turn);
    }
  }
}
