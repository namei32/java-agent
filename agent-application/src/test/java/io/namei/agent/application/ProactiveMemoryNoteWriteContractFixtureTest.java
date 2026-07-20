package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Consumes the frozen P6 Contract before any real SQLite DML implementation exists. */
@Tag("compat")
class ProactiveMemoryNoteWriteContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void hasUniqueP6ContractCasesAndNoProductionDefaults() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("proactive/r14-proactive-memory-note-write-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("proactive/r14-proactive-memory-note-write-v1");
    assertThat(fixture.path("cases")).hasSizeGreaterThanOrEqualTo(30);
    assertThat(fixture.path("defaults").path("capability").asString())
        .isEqualTo("proactive_memory_note_write");
    assertThat(fixture.path("defaults").path("memoryType").asString()).isEqualTo("NOTE");
    assertThat(fixture.path("defaults").path("emotionalWeight").asInt()).isZero();
    assertThat(fixture.path("defaults").path("database").asString())
        .isEqualTo("TEMP_JUNIT_AGENT_MEMORY_DB");
    assertThat(fixture.path("defaults").path("retention").asString()).isEqualTo("TEST_LIFETIME");

    Set<String> ids = new HashSet<>();
    for (JsonNode testCase : fixture.path("cases")) {
      assertThat(testCase.path("id").asString()).isNotBlank();
      assertThat(ids.add(testCase.path("id").asString())).isTrue();
      assertThat(testCase.path("group").asString()).isNotBlank();
      assertThat(testCase.path("input").isObject()).isTrue();
      assertThat(testCase.path("expected").isObject()).isTrue();
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
