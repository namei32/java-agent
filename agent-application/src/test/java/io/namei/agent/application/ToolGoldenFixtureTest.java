package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ToolGoldenFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void validatesPythonToolMessageProjection() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/message-envelope.json"));

    assertThat(fixture.path("source").asString()).isEqualTo("python-reference");
    assertThat(fixture.path("cases")).hasSize(2);
    for (JsonNode testCase : fixture.path("cases")) {
      JsonNode input = testCase.path("input");
      JsonNode messages = testCase.path("expected").path("messages");
      assertThat(input.path("toolDefinitions").path(0).path("function").path("name").asString())
          .isEqualTo("golden_lookup");
      assertThat(messages.path(0).path("role").asString()).isEqualTo("assistant");
      assertThat(messages.path(0).path("tool_calls")).hasSize(input.path("toolCalls").size());
      assertThat(messages.size()).isEqualTo(input.path("toolCalls").size() + 1);

      for (int index = 0; index < input.path("toolCalls").size(); index++) {
        JsonNode call = input.path("toolCalls").path(index);
        JsonNode rendered = messages.path(0).path("tool_calls").path(index);
        assertThat(rendered.path("id").asString()).isEqualTo(call.path("id").asString());
        assertThat(rendered.path("function").path("name").asString())
            .isEqualTo(call.path("name").asString());
        assertThat(JSON.readTree(rendered.path("function").path("arguments").asString()))
            .isEqualTo(call.path("arguments"));

        JsonNode result = messages.path(index + 1);
        assertThat(result.path("role").asString()).isEqualTo("tool");
        assertThat(result.path("tool_call_id").asString()).isEqualTo(call.path("id").asString());
      }
    }
  }

  @Test
  void validatesApprovedMinimalLoopCasesAndSafeLifecycleProjection() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/minimal-loop.json"));

    assertThat(fixture.path("source").asString()).isEqualTo("migration-contract");
    var identifiers = new HashSet<String>();
    var outcomes = new ArrayList<String>();
    for (JsonNode testCase : fixture.path("cases")) {
      assertThat(identifiers.add(testCase.path("id").asString())).isTrue();
      JsonNode expected = testCase.path("expected");
      String outcome = expected.path("outcome").asString();
      outcomes.add(outcome);
      assertThat(expected.path("committed").asBoolean()).isEqualTo(outcome.equals("COMPLETED"));
      assertThat(expected.path("trace").path(0).path("type").asString())
          .isEqualTo("TURN_STARTED");
      for (JsonNode event : expected.path("trace")) {
        assertThat(event.has("arguments")).isFalse();
        assertThat(event.has("content")).isFalse();
        assertThat(event.has("result")).isFalse();
        assertThat(event.has("exception")).isFalse();
      }
    }

    assertThat(identifiers)
        .containsExactlyInAnyOrder(
            "direct-answer",
            "single-tool-success",
            "multiple-tools-preserve-order",
            "unknown-tool-recovers",
            "tool-error-recovers",
            "invalid-model-response",
            "iteration-limit-does-not-commit");
    assertThat(outcomes)
        .contains("COMPLETED", "INVALID_MODEL_RESPONSE", "TOOL_LOOP_LIMIT_EXCEEDED");
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
