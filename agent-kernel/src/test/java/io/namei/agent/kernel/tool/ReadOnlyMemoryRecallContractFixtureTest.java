package io.namei.agent.kernel.tool;

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
class ReadOnlyMemoryRecallContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void freezesTwentyFourCurrentScopeReadOnlyMemoryRecallCases() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/read-only-memory-recall-v1.json"));
    JsonNode cases = fixture.path("cases");

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("tools/read-only-memory-recall-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("contractEvidence").path("approvedOn").asString())
        .isEqualTo("2026-07-19");
    assertThat(cases).hasSize(24);

    Map<String, Long> groups =
        StreamSupport.stream(cases.spliterator(), false)
            .map(testCase -> testCase.path("group").asString())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(groups)
        .containsExactlyInAnyOrderEntriesOf(
            Map.ofEntries(
                Map.entry("activation", 6L),
                Map.entry("definition", 1L),
                Map.entry("catalog", 2L),
                Map.entry("schema", 6L),
                Map.entry("scope", 2L),
                Map.entry("projection", 3L),
                Map.entry("ranking", 1L),
                Map.entry("failure", 3L)));
    assertThat(fixture.path("limits").path("maxQueryCodePoints").asInt()).isEqualTo(256);
    assertThat(fixture.path("limits").path("defaultLimit").asInt()).isEqualTo(8);
    assertThat(fixture.path("limits").path("maxLimit").asInt()).isEqualTo(20);
    assertThat(fixture.path("limits").path("maxProjectedCodePoints").asInt()).isEqualTo(12_000);

    Set<String> modes = Set.of("DISABLED", "CURRENT_SCOPE_READ_ONLY");
    for (JsonNode testCase : cases) {
      String mode = testCase.path("input").path("mode").asString();
      if (!mode.isEmpty() && mode.equals(mode.toUpperCase())) {
        assertThat(modes).contains(mode);
      }
    }

    String rawSession = fixture.path("defaults").path("rawSession").asString();
    String rawScope = fixture.path("defaults").path("scopeBinding").asString();
    for (JsonNode testCase : cases) {
      if ("privacy".equals(testCase.path("group").asString())) {
        continue;
      }
      String expected = JSON.writeValueAsString(testCase.path("expected"));
      assertThat(expected).as(testCase.path("id").asString()).doesNotContain(rawSession, rawScope);
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
