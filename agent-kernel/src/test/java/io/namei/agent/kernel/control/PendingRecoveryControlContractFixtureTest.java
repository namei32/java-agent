package io.namei.agent.kernel.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class PendingRecoveryControlContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void freezesTwentyFourPendingRecoveryControlCasesWithoutCreatingARuntimeSurface()
      throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("control-plane/pending-recovery-control-v1.json"));
    JsonNode cases = fixture.path("cases");

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("pending-recovery-control-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(cases).hasSize(24);
    Map<String, Long> groups =
        StreamSupport.stream(cases.spliterator(), false)
            .map(testCase -> testCase.path("group").asString())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(groups)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "activation", 4L,
                "request-shape", 6L,
                "resume", 6L,
                "cancel", 4L,
                "status", 4L));
    assertThat(fixture.path("limits").path("allowedActions"))
        .extracting(JsonNode::asString)
        .containsExactly("STATUS", "RESUME", "CANCEL");

    String rawSession = fixture.path("defaults").path("rawSession").asString();
    String rawTool = fixture.path("defaults").path("rawTool").asString();
    String rawArguments = fixture.path("defaults").path("rawArguments").asString();
    String rawResult = fixture.path("defaults").path("rawResult").asString();
    String rawApproval = fixture.path("defaults").path("rawApproval").asString();
    Set<String> allowedActions = Set.of("STATUS", "RESUME", "CANCEL");
    for (JsonNode testCase : cases) {
      String action = testCase.path("input").path("action").asString();
      if (!action.isEmpty() && action.equals(action.toUpperCase())) {
        assertThat(allowedActions).contains(action);
      }
      String expected = JSON.writeValueAsString(testCase.path("expected"));
      assertThat(expected)
          .as(testCase.path("id").asString())
          .doesNotContain(rawSession, rawTool, rawArguments, rawResult, rawApproval);
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
