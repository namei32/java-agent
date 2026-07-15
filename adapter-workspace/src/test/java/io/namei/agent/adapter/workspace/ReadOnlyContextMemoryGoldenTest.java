package io.namei.agent.adapter.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ContextAssembler;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ReadOnlyContextMemoryGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @TestFactory
  Stream<DynamicTest> matchesPythonReadOnlyContextMemoryProjection() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            Path.of(System.getProperty("golden.root"))
                .resolve("context/read-only-context-memory.json"));
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode testCase : fixture.path("cases")) {
      tests.add(
          DynamicTest.dynamicTest(testCase.path("id").asString(), () -> verifyCase(testCase)));
    }
    return tests.stream();
  }

  private void verifyCase(JsonNode testCase) throws Exception {
    JsonNode input = testCase.path("input");
    Path workspace = temporaryDirectory.resolve(testCase.path("id").asString());
    writeProfile(workspace, "SELF.md", input.path("selfModel").asString());
    writeProfile(workspace, "MEMORY.md", input.path("longTermMemory").asString());
    writeProfile(workspace, "RECENT_CONTEXT.md", input.path("recentContext").asString());
    var profile = new MarkdownMemoryProfileAdapter(workspace, 65_536).load();
    var assembled =
        new ContextAssembler()
            .assemble(
                input.path("basePrompt").asString(),
                profile,
                messages(input.path("history")),
                input.path("retrievedMemory").asString(),
                disabledSections(input.path("disabledSections")),
                new ChatMessage(MessageRole.USER, input.path("currentMessage").asString()));
    JsonNode expected = testCase.path("expected");

    assertThat(assembled.systemPrompt()).isEqualTo(expected.path("systemPrompt").asString());
    assertThat(assembled.contextFrame()).isEqualTo(expected.path("contextFrame").asString());
    assertThat(assembled.sectionNames())
        .containsExactlyElementsOf(strings(expected.path("sectionNames")));
    assertThat(assembled.messages()).containsExactlyElementsOf(messages(expected.path("messages")));
  }

  private static void writeProfile(Path workspace, String fileName, String content)
      throws Exception {
    if (content.isEmpty()) {
      return;
    }
    Path memory = Files.createDirectories(workspace.resolve("memory"));
    Files.writeString(memory.resolve(fileName), content);
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

  private static Set<ContextAssembler.Section> disabledSections(JsonNode nodes) {
    var sections = new HashSet<ContextAssembler.Section>();
    for (JsonNode node : nodes) {
      sections.add(ContextAssembler.Section.fromExternalName(node.asString()));
    }
    return Set.copyOf(sections);
  }

  private static List<String> strings(JsonNode nodes) {
    var values = new ArrayList<String>();
    for (JsonNode node : nodes) {
      values.add(node.asString());
    }
    return List.copyOf(values);
  }
}
