package io.namei.agent.kernel.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class PythonHistoryGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> matchesPythonCompleteTurnProjection() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("history/session-history.json"));
    var selector = new ConversationHistorySelector();
    var tests = new ArrayList<DynamicTest>();

    for (JsonNode testCase : fixture.path("cases")) {
      tests.add(
          DynamicTest.dynamicTest(
              testCase.path("id").asString(),
              () -> {
                JsonNode input = testCase.path("input");
                List<ChatMessage> actual =
                    selector.select(
                        messages(input.path("messages")),
                        new HistoryLimits(
                            input.path("maxMessages").asInt(),
                            input.path("maxCharacters").asInt()));
                assertThat(actual)
                    .containsExactlyElementsOf(
                        messages(testCase.path("expected").path("messages")));
              }));
    }
    return tests.stream();
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
}
