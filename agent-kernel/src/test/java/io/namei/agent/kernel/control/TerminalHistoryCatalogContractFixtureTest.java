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

/** Freezes R13-C2-A metadata-only history; runtime consumption stays in bootstrap. */
@Tag("compat")
class TerminalHistoryCatalogContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void freezesTwentyTwoInMemoryTerminalCatalogCasesWithoutDetailAuthority() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("control-plane/r13-terminal-history-catalog-v1.json"));
    JsonNode cases = fixture.path("cases");

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("control-plane/r13-terminal-history-catalog-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("contractEvidence").path("approvedOn").asString())
        .isEqualTo("2026-07-19");
    assertThat(fixture.path("contractEvidence").path("adr").asString())
        .isEqualTo("docs/adr/0036-restrict-control-history-to-in-memory-terminal-metadata.md");
    assertThat(cases).hasSize(22);
    Map<String, Long> groups =
        StreamSupport.stream(cases.spliterator(), false)
            .map(testCase -> testCase.path("group").asString())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(groups)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "activation", 4L,
                "authentication", 3L,
                "projection", 6L,
                "pagination", 5L,
                "secrecy", 4L));
    assertThat(fixture.path("limits").path("defaultPageSize").asInt()).isEqualTo(20);
    assertThat(fixture.path("limits").path("maximumPageSize").asInt()).isEqualTo(50);
    assertThat(fixture.path("limits").path("cursorTtlSeconds").asInt()).isEqualTo(60);

    Set<String> rawValues =
        Set.of(
            fixture.path("defaults").path("rawContent").asString(),
            fixture.path("defaults").path("rawRoute").asString(),
            fixture.path("defaults").path("rawSession").asString(),
            fixture.path("defaults").path("rawTurn").asString());
    for (JsonNode testCase : cases) {
      String expected = JSON.writeValueAsString(testCase.path("expected"));
      for (String rawValue : rawValues) {
        assertThat(expected).as(testCase.path("id").asString()).doesNotContain(rawValue);
      }
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
