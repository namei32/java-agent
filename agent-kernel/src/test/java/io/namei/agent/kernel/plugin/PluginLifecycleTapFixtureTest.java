package io.namei.agent.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.namei.agent.kernel.lifecycle.TurnEventType;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class PluginLifecycleTapFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String HASH = "a".repeat(64);

  @Test
  void executesEveryVersionedLifecycleTapCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("plugins/lifecycle-tap-v2.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("plugins/lifecycle-tap-v2");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(16);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase);
    }
  }

  private static void verify(JsonNode testCase) {
    String id = testCase.path("id").asString();
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    try {
      switch (testCase.path("group").asString()) {
        case "manifest" -> manifest(input);
        case "projection" -> projection(input, expected);
        case "event" -> tapEvent(input);
        default -> throw new AssertionError("未知 Lifecycle Fixture 分组: " + id);
      }
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + id);
      }
    } catch (PluginContractViolation violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + id, violation);
      }
      assertThat(violation.code().name()).as(id).isEqualTo(expected.path("code").asString());
    }
  }

  private static void manifest(JsonNode input) {
    new PluginManifest(
        PluginContract.CURRENT_VERSION,
        PluginId.parse("lifecycle-observer"),
        "2.0.0",
        input.path("apiVersion").asInt(),
        PluginKind.JAVA_SERVICE,
        names(input.path("capabilities")).stream().map(PluginCapability::parse).toList());
  }

  private static void projection(JsonNode input, JsonNode expected) {
    var projected = PluginLifecycleProjection.project(lifecycleEvent(input));
    if (!expected.path("projected").asBoolean(true)) {
      assertThat(projected).isEmpty();
      return;
    }
    PluginTapEvent event = projected.orElseThrow();
    assertThat(event.capability()).isEqualTo(PluginCapability.LIFECYCLE_TAP);
    assertThat(event.phase().name()).isEqualTo(expected.path("phase").asString());
    assertThat(event.outcome().name()).isEqualTo(expected.path("outcome").asString());
    assertThat(event.referenceHash()).matches("[0-9a-f]{64}");
  }

  private static void tapEvent(JsonNode input) {
    new PluginTapEvent(
        PluginCapability.parse(input.path("capability").asString()),
        PluginLifecyclePhase.valueOf(input.path("phase").asString()),
        HASH,
        PluginTapOutcome.ACCEPTED,
        null,
        0);
  }

  private static TurnLifecycleEvent lifecycleEvent(JsonNode input) {
    String type = input.path("type").asString();
    return switch (TurnEventType.valueOf(type)) {
      case TURN_STARTED -> TurnLifecycleEvent.turnStarted();
      case MODEL_REQUESTED -> TurnLifecycleEvent.modelRequested(1);
      case MODEL_COMPLETED -> TurnLifecycleEvent.modelCompleted(1, input.path("status").asString());
      case TOOL_CALL_STARTED -> TurnLifecycleEvent.toolStarted(1, "call-1", "read_file");
      case TOOL_CALL_COMPLETED ->
          TurnLifecycleEvent.toolCompleted(1, "call-1", "read_file", ToolResultStatus.SUCCESS);
      case TURN_COMMITTED -> TurnLifecycleEvent.turnCommitted();
      case TURN_FAILED -> TurnLifecycleEvent.turnFailed("MODEL_TIMEOUT");
      case APPROVAL_REQUESTED -> TurnLifecycleEvent.approvalRequested(1, "call-1", "read_file");
      default -> throw new AssertionError("Fixture 未实现事件: " + type);
    };
  }

  private static List<String> names(JsonNode values) {
    var parsed = new java.util.ArrayList<String>();
    for (JsonNode value : values) {
      parsed.add(value.asString());
    }
    return List.copyOf(parsed);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
