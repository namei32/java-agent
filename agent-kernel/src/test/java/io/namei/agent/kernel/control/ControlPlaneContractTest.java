package io.namei.agent.kernel.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ControlPlaneContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void fixesFortyEightVersionedControlPlaneCases() throws Exception {
    JsonNode fixture = fixture();
    JsonNode cases = fixture.path("cases");

    assertThat(fixture.path("formatVersion").asInt())
        .isEqualTo(ControlPlaneContract.CURRENT_VERSION);
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(cases).hasSize(48);

    Map<String, Long> groups =
        StreamSupport.stream(cases.spliterator(), false)
            .map(testCase -> testCase.path("group").asString())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(groups)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "mode-security", 8L,
                "session-auth", 8L,
                "status-turn", 8L,
                "cancellation", 10L,
                "sse", 10L,
                "shutdown-failure", 4L));

    for (JsonNode testCase : cases) {
      JsonNode expected = testCase.path("expected");
      if (expected.has("code") && expected.path("code").asString().startsWith("CONTROL_")) {
        assertThat(ControlStableCode.parse(expected.path("code").asString()).name())
            .as(testCase.path("id").asString())
            .isEqualTo(expected.path("code").asString());
      }
      if (expected.has("result")) {
        assertThat(ControlCancelResult.valueOf(expected.path("result").asString()).name())
            .as(testCase.path("id").asString())
            .isEqualTo(expected.path("result").asString());
      }
      if (expected.has("state")
          && (expected.path("state").asString().equals("ACTIVE")
              || expected.path("state").asString().equals("CANCELLATION_REQUESTED"))) {
        assertThat(ControlTurnState.valueOf(expected.path("state").asString()).name())
            .as(testCase.path("id").asString())
            .isEqualTo(expected.path("state").asString());
      }
    }
  }

  @Test
  void fixesStableStatesAndRetryability() {
    assertThat(ControlTurnState.values())
        .containsExactly(ControlTurnState.ACTIVE, ControlTurnState.CANCELLATION_REQUESTED);
    assertThat(ControlCancelResult.values())
        .containsExactly(
            ControlCancelResult.CANCELLATION_REQUESTED,
            ControlCancelResult.ALREADY_REQUESTED,
            ControlCancelResult.ALREADY_CANCELLED,
            ControlCancelResult.ALREADY_TERMINAL,
            ControlCancelResult.NOT_FOUND);
    assertThat(ControlTerminalKind.values())
        .containsExactly(
            ControlTerminalKind.COMPLETED,
            ControlTerminalKind.CANCELLED,
            ControlTerminalKind.FAILED,
            ControlTerminalKind.SOURCE_ENDED);

    assertThat(ControlStableCode.CONTROL_SNAPSHOT_UNAVAILABLE.retryable()).isTrue();
    assertThat(ControlStableCode.CONTROL_SHUTTING_DOWN.retryable()).isTrue();
    assertThat(ControlStableCode.PENDING_RECOVERY_UNAVAILABLE.retryable()).isTrue();
    assertThat(ControlStableCode.values())
        .filteredOn(code -> !code.retryable())
        .doesNotContain(
            ControlStableCode.CONTROL_SNAPSHOT_UNAVAILABLE,
            ControlStableCode.CONTROL_SHUTTING_DOWN,
            ControlStableCode.PENDING_RECOVERY_UNAVAILABLE);
  }

  @Test
  void validatesAndRedactsOpaqueTurnReferences() {
    byte[] bytes = new byte[ControlPlaneContract.TURN_REFERENCE_BYTES];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) index;
    }

    ControlTurnRef reference = ControlTurnRef.fromBytes(bytes);

    assertThat(reference.value()).isEqualTo("AAECAwQFBgcICQoLDA0ODw");
    assertThat(ControlTurnRef.parse(reference.value())).isEqualTo(reference);
    assertThat(reference.toString()).doesNotContain(reference.value());
    assertThatThrownBy(() -> ControlTurnRef.parse("raw-turn-secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ControlTurnRef.fromBytes(new byte[15]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static JsonNode fixture() throws Exception {
    return JSON.readTree(goldenRoot().resolve("control-plane/loopback-control-plane-v1.json"));
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
