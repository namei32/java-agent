package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.port.ProactiveJobInspectionPort;
import io.namei.agent.kernel.proactive.ProactiveJobInspectionSnapshot;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolResult;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ProactiveJobInspectionToolsetTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void remainsDeferredAndProjectsOnlyActiveSafeFields() throws Exception {
    ToolRegistry registry = registry(port(List.of(at("daily-summary"), every("hourly-summary"))));
    ToolCatalogSession session = registry.newCatalogSession();

    assertThat(execute(registry, session, Map.of()).content()).isEqualTo("工具不可用。");

    registry.execute(
        new ToolCall("search", "tool_search", Map.of("query", "select:list_local_proactive_jobs")),
        TurnCancellation.none(),
        session);
    ToolResult result = execute(registry, session, Map.of());

    JsonNode payload = JSON.readTree(result.content());
    assertThat(result.status().name()).isEqualTo("SUCCESS");
    assertThat(payload.path("count").asInt()).isEqualTo(2);
    assertThat(payload.path("limit").asInt()).isEqualTo(16);
    assertThat(payload.path("jobs").get(0).properties())
        .extracting(Map.Entry::getKey)
        .containsExactly("job_ref", "schedule", "next_run_at", "state", "attempts", "max_attempts");
    assertThat(payload.path("jobs").get(1).properties())
        .extracting(Map.Entry::getKey)
        .containsExactly(
            "job_ref",
            "schedule",
            "next_run_at",
            "every_seconds",
            "state",
            "attempts",
            "max_attempts");
    assertThat(result.content())
        .doesNotContain("target_hash")
        .doesNotContain("idempotency_key")
        .doesNotContain("owner_id")
        .doesNotContain("lease_expires_at")
        .doesNotContain("revision")
        .doesNotContain("database_path");
  }

  @Test
  void rejectsInvalidArgumentsAndFailsClosedForUnsafeOrUnavailablePorts() {
    ToolRegistry registry = registry(port(List.of(at("daily-summary"))));
    ToolCatalogSession session = unlock(registry);

    assertThat(execute(registry, session, Map.of("limit", new BigDecimal("1.0"))).content())
        .isEqualTo("PROACTIVE_JOB_INSPECTION_INVALID_ARGUMENT");
    assertThat(execute(registry, session, Map.of("limit", 33)).content())
        .isEqualTo("PROACTIVE_JOB_INSPECTION_INVALID_ARGUMENT");
    assertThat(execute(registry, session, Map.of("limit", 1, "target_hash", "private")).content())
        .isEqualTo("PROACTIVE_JOB_INSPECTION_INVALID_ARGUMENT");

    ToolRegistry unavailable =
        registry(
            limit -> {
              throw new IllegalStateException("private sqlite");
            });
    assertThat(execute(unavailable, unlock(unavailable), Map.of("limit", 1)).content())
        .isEqualTo("PROACTIVE_JOB_INSPECTION_UNAVAILABLE");

    ToolRegistry unsafeOrder = registry(port(List.of(at("zeta-summary"), at("alpha-summary"))));
    assertThat(execute(unsafeOrder, unlock(unsafeOrder), Map.of("limit", 2)).content())
        .isEqualTo("PROACTIVE_JOB_INSPECTION_UNAVAILABLE");
  }

  @Test
  void consumesEveryToolAndProjectionFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            goldenRoot().resolve("proactive/read-only-local-proactive-job-inspection-v1.json"));
    assertThat(fixture.path("cases")).hasSize(13);
    assertThat(ProactiveJobInspectionToolset.enabled(port(List.of(at("daily-summary")))).tools())
        .extracting(tool -> tool.definition().name())
        .containsExactly(fixture.path("tool").path("name").asString());
    assertThat(ProactiveJobInspectionToolset.enabled(port(List.of(at("daily-summary")))).tools())
        .extracting(tool -> tool.definition().version())
        .containsExactly(fixture.path("tool").path("version").asString());

    for (JsonNode testCase : fixture.path("cases")) {
      switch (testCase.path("group").asString()) {
        case "tool" -> verifyToolFixtureCase(testCase);
        case "projection" -> verifyProjectionFixtureCase(testCase);
        default -> {
          // Kernel, SQLite and Bootstrap tests consume their own Fixture groups.
        }
      }
    }
  }

  private static ToolRegistry registry(ProactiveJobInspectionPort port) {
    return new ToolRegistry(
        new ToolCatalog(
            ProactiveJobInspectionToolset.enabled(port).tools().stream()
                .map(
                    tool ->
                        new ToolCatalogEntry(
                            tool,
                            ToolCatalogVisibility.DEFERRED,
                            ToolCatalogSource.BUILTIN,
                            "",
                            List.of("计划", "调度", "proactive", "任务")))
                .toList()),
        ToolRuntimeSettings.readOnlyDefaults());
  }

  private static void verifyToolFixtureCase(JsonNode testCase) throws Exception {
    JsonNode expected = testCase.path("expected");
    ToolRegistry registry =
        "unavailable-port-fails-closed".equals(testCase.path("id").asString())
            ? registry(
                limit -> {
                  throw new IllegalStateException("private sqlite");
                })
            : registry(port(List.of(at("daily-summary"))));
    ToolResult result = execute(registry, unlock(registry), arguments(testCase.path("input")));
    if (expected.has("code")) {
      assertThat(result.content())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("code").asString());
      return;
    }
    JsonNode payload = JSON.readTree(result.content());
    assertThat(payload.path("limit").asInt())
        .as(testCase.path("id").asString())
        .isEqualTo(expected.path("limit").asInt());
  }

  private static void verifyProjectionFixtureCase(JsonNode testCase) {
    ToolRegistry registry = registry(port(List.of(at("daily-summary"))));
    ToolResult result = execute(registry, unlock(registry), arguments(testCase.path("input")));
    for (JsonNode absent : testCase.path("expected").path("absentFields")) {
      assertThat(result.content())
          .as(testCase.path("id").asString())
          .doesNotContain(absent.asString());
    }
  }

  private static Map<String, Object> arguments(JsonNode input) {
    if (!input.has("limit")) {
      return Map.of();
    }
    return input.path("limit").isFloatingPointNumber()
        ? Map.of("limit", input.path("limit").asDouble())
        : Map.of("limit", input.path("limit").asInt());
  }

  private static ToolCatalogSession unlock(ToolRegistry registry) {
    ToolCatalogSession session = registry.newCatalogSession();
    registry.execute(
        new ToolCall("search", "tool_search", Map.of("query", "select:list_local_proactive_jobs")),
        TurnCancellation.none(),
        session);
    return session;
  }

  private static ToolResult execute(
      ToolRegistry registry, ToolCatalogSession session, Map<String, Object> arguments) {
    return registry.execute(
        new ToolCall("inspect", "list_local_proactive_jobs", arguments),
        TurnCancellation.none(),
        session);
  }

  private static ProactiveJobInspectionPort port(List<ProactiveJobInspectionSnapshot> snapshots) {
    return ignored -> snapshots;
  }

  private static ProactiveJobInspectionSnapshot at(String jobRef) {
    return new ProactiveJobInspectionSnapshot(
        ProactiveJobRef.parse(jobRef),
        new ProactiveSchedule(
            ProactiveScheduleKind.AT, Instant.parse("2026-07-20T00:00:00Z"), null),
        ProactiveJobState.SCHEDULED,
        0,
        3);
  }

  private static ProactiveJobInspectionSnapshot every(String jobRef) {
    return new ProactiveJobInspectionSnapshot(
        ProactiveJobRef.parse(jobRef),
        new ProactiveSchedule(
            ProactiveScheduleKind.EVERY,
            Instant.parse("2026-07-20T01:00:00Z"),
            Duration.ofHours(1)),
        ProactiveJobState.CLAIMED,
        1,
        3);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
