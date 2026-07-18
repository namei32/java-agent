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
class ConversationEvidenceContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void freezesTwentySixCurrentSessionConversationEvidenceCases() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/conversation-evidence-v1.json"));
    JsonNode cases = fixture.path("cases");

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("tools/conversation-evidence-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("contractEvidence").path("approvedOn").asString())
        .isEqualTo("2026-07-19");
    assertThat(cases).hasSize(26);

    Map<String, Long> groups =
        StreamSupport.stream(cases.spliterator(), false)
            .map(testCase -> testCase.path("group").asString())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(groups)
        .containsExactlyInAnyOrderEntriesOf(
            Map.ofEntries(
                Map.entry("activation", 5L),
                Map.entry("definition", 2L),
                Map.entry("catalog", 2L),
                Map.entry("fetch-schema", 5L),
                Map.entry("fetch-result", 1L),
                Map.entry("search-schema", 3L),
                Map.entry("search-result", 1L),
                Map.entry("scope", 3L),
                Map.entry("projection", 2L),
                Map.entry("privacy", 1L),
                Map.entry("failure", 1L)));
    assertThat(fixture.path("limits").path("maxFetchIds").asInt()).isEqualTo(16);
    assertThat(fixture.path("limits").path("maxContext").asInt()).isEqualTo(10);
    assertThat(fixture.path("limits").path("maxSearchLimit").asInt()).isEqualTo(50);
    assertThat(fixture.path("limits").path("maxSearchOffset").asInt()).isEqualTo(1_000);
    assertThat(fixture.path("limits").path("maxFetchItems").asInt()).isEqualTo(16);
    assertThat(fixture.path("limits").path("maxProjectedCodePoints").asInt()).isEqualTo(12_000);
    assertThat(fixture.path("limits").path("maxPreviewLines").asInt()).isEqualTo(50);

    Set<String> modes = Set.of("DISABLED", "CURRENT_SESSION_READ_ONLY");
    for (JsonNode testCase : cases) {
      String mode = testCase.path("input").path("mode").asString();
      if (!mode.isEmpty() && mode.equals(mode.toUpperCase())) {
        assertThat(modes).contains(mode);
      }
    }

    String rawSession = fixture.path("defaults").path("rawSession").asString();
    String rawRoute = fixture.path("defaults").path("rawRoute").asString();
    String rawToolChain = fixture.path("defaults").path("rawToolChain").asString();
    for (JsonNode testCase : cases) {
      if ("privacy".equals(testCase.path("group").asString())) {
        // The privacy case names forbidden values as assertion metadata; it is not a projection.
        continue;
      }
      String expected = JSON.writeValueAsString(testCase.path("expected"));
      assertThat(expected)
          .as(testCase.path("id").asString())
          .doesNotContain(rawSession, rawRoute, rawToolChain);
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
