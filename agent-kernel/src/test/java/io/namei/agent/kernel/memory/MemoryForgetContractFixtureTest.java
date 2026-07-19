package io.namei.agent.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class MemoryForgetContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final MemoryScope SCOPE = new MemoryScope("a".repeat(64));
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

  @Test
  void consumesEveryKernelFixtureCaseAndFixesSafeMemoryForgetValues() throws Exception {
    JsonNode fixture = readFixture();

    assertThat(fixture.path("suite").asString()).isEqualTo("tools/memory-forget-capability-v1");
    assertThat(fixture.path("cases").size()).isEqualTo(21);
    assertThat(MemoryLifecycleState.values())
        .containsExactly(MemoryLifecycleState.ACTIVE, MemoryLifecycleState.SUPERSEDED);

    for (JsonNode testCase : fixture.path("cases")) {
      switch (testCase.path("id").asString()) {
        case "forget-normalizes-strip-drops-blanks-and-stably-deduplicates" ->
            assertThat(
                    MemoryForgetCommand.normalizeIds(strings(testCase.path("input").path("ids"))))
                .containsExactly("memory-a", "memory-b");
        case "forget-normalization-preserves-first-occurrence-and-case" ->
            assertThat(
                    MemoryForgetCommand.normalizeIds(strings(testCase.path("input").path("ids"))))
                .containsExactly("A", "a");
        case "forget-empty-normalized-input-is-immediate-safe-success" ->
            assertThat(MemoryForgetResult.empty().safeProjection())
                .containsEntry("requested_ids", List.of())
                .containsEntry("superseded_ids", List.of())
                .containsEntry("missing_ids", List.of())
                .containsEntry("count", 0);
        case "forget-result-preserves-request-hit-and-missing-order" -> {
          var result =
              new MemoryForgetResult(
                  List.of("memory-b", "memory-a", "missing"),
                  List.of("memory-b", "memory-a"),
                  List.of("missing"));
          assertThat(result.safeProjection())
              .containsEntry("requested_ids", List.of("memory-b", "memory-a", "missing"))
              .containsEntry("superseded_ids", List.of("memory-b", "memory-a"))
              .containsEntry("missing_ids", List.of("missing"))
              .containsEntry("count", 2)
              .doesNotContainKeys(
                  "items",
                  "content",
                  "embedding",
                  "content_hash",
                  "scope",
                  "session",
                  "approval",
                  "capsule",
                  "ledger");
        }
        default -> {
          // Adapter, Bootstrap, and recovery cases are consumed by their owning vertical tests.
        }
      }
    }
  }

  @Test
  void rejectsNonCanonicalRequestsAndInconsistentResultsWithoutLeakingSensitiveValues() {
    assertThatThrownBy(
            () ->
                new MemoryForgetCommand(
                    SCOPE, "forget-op-001", List.of(" memory-a "), "b".repeat(64), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemoryForgetResult(
                    List.of("memory-a"), List.of("memory-a"), List.of("memory-a")))
        .isInstanceOf(IllegalArgumentException.class);

    var command =
        new MemoryForgetCommand(SCOPE, "forget-op-001", List.of("memory-a"), "b".repeat(64), NOW);
    assertThat(command.toString())
        .doesNotContain(
            SCOPE.binding(), command.operationKey(), command.argumentHash(), "memory-a");
  }

  private static List<String> strings(JsonNode node) {
    var values = new java.util.ArrayList<String>();
    node.forEach(value -> values.add(value.asString()));
    return List.copyOf(values);
  }

  private static JsonNode readFixture() throws Exception {
    return JSON.readTree(goldenRoot().resolve("tools/memory-forget-capability-v1.json").toFile());
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
