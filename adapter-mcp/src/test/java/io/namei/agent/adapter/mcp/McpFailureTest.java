package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("failure")
class McpFailureTest {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"malformed-json", "stdout-noise", "wrong-id"})
  void rejectsInvalidWireWithoutPublishingToolsOrLeakingServerContent(String scenario) {
    McpRuntime runtime = runtime(McpReferenceServerSupport.server(temp, scenario));
    try {
      assertThat(runtime.tools()).isEmpty();
      assertThat(runtime.status())
          .extracting(
              McpRuntimeStatus::state,
              McpRuntimeStatus::readyServers,
              McpRuntimeStatus::unavailableServers)
          .containsExactly(McpRuntimeStatus.State.DEGRADED, 0, 1);
      assertThat(runtime.status().toString())
          .doesNotContain(
              "private", scenario, temp.toString(), System.getProperty("java.class.path"));
    } finally {
      runtime.close();
    }
  }

  @Test
  void isolatesOneBrokenServerWhileAnotherServerRemainsCallable() {
    McpSettings settings = McpReferenceServerSupport.settings(temp, 1_048_576, 1);
    McpRuntime runtime =
        McpRuntimes.staticReadOnly(
            new McpConfiguration(
                1,
                List.of(
                    McpReferenceServerSupport.server(temp, "broken", "malformed-json"),
                    McpReferenceServerSupport.server(temp, "docs", "normal"))),
            settings);
    try {
      assertThat(runtime.status())
          .extracting(
              McpRuntimeStatus::state,
              McpRuntimeStatus::readyServers,
              McpRuntimeStatus::unavailableServers)
          .containsExactly(McpRuntimeStatus.State.DEGRADED, 1, 1);
      Tool echo = tool(runtime, "mcp_docs__echo");
      assertThat(echo.execute(Map.of("text", "healthy")).content()).isEqualTo("healthy");
      assertThat(runtime.tools())
          .extracting(item -> item.definition().name())
          .noneMatch(name -> name.startsWith("mcp_broken__"));
    } finally {
      runtime.close();
    }
  }

  @Test
  void doesNotReplayFailedCallAndReconnectsAtMostOnceForTheNextCall() throws Exception {
    Path ready = temp.resolve("allow-reconnect");
    McpRuntime runtime =
        runtime(
            McpReferenceServerSupport.server(
                temp, "docs", "sudden-until-marker", ready.toString()));
    try {
      Tool echo = tool(runtime, "mcp_docs__echo");
      assertThat(echo.execute(Map.of("text", "must-not-replay")).status())
          .isEqualTo(ToolResultStatus.ERROR);
      assertThat(runtime.status().unavailableServers()).isEqualTo(1);

      Files.createFile(ready);
      assertThat(echo.execute(Map.of("text", "new-explicit-call")).content())
          .isEqualTo("new-explicit-call");
      assertThat(runtime.status().state()).isEqualTo(McpRuntimeStatus.State.READY);
    } finally {
      runtime.close();
    }
  }

  @Test
  void marksServerStaleOnListChangeAndNeverUsesTheWrapperAgain() {
    McpRuntime runtime = runtime(McpReferenceServerSupport.server(temp, "list-changed"));
    try {
      Tool echo = tool(runtime, "mcp_docs__echo");
      assertThat(echo.execute(Map.of("text", "discard-after-change")).content())
          .isEqualTo("MCP 工具不可用。");
      assertThat(runtime.status())
          .extracting(McpRuntimeStatus::state, McpRuntimeStatus::staleServers)
          .containsExactly(McpRuntimeStatus.State.DEGRADED, 1);
      assertThat(echo.execute(Map.of("text", "must-not-run")).content()).isEqualTo("MCP 工具不可用。");
    } finally {
      runtime.close();
    }
  }

  @Test
  void keepsServerStaleWhenReconnectCatalogFingerprintChanges() throws Exception {
    Path ready = temp.resolve("allow-reconnect");
    Path changed = temp.resolve("changed-catalog");
    McpRuntime runtime =
        runtime(
            McpReferenceServerSupport.server(
                temp, "docs", "catalog-change", ready.toString(), changed.toString()));
    try {
      Tool echo = tool(runtime, "mcp_docs__echo");
      assertThat(echo.execute(Map.of("text", "first-fails")).status())
          .isEqualTo(ToolResultStatus.ERROR);
      Files.createFile(ready);
      Files.createFile(changed);

      assertThat(echo.execute(Map.of("text", "must-not-run-on-changed-catalog")).content())
          .isEqualTo("MCP 工具不可用。");
      assertThat(runtime.status().staleServers()).isEqualTo(1);
      assertThat(echo.execute(Map.of("text", "still-fail-closed")).content())
          .isEqualTo("MCP 工具不可用。");
    } finally {
      runtime.close();
    }
  }

  @Test
  void closeRacingWithReconnectCannotReviveTheProcessOrLeakTheCall() throws Exception {
    Path ready = temp.resolve("allow-slow-reconnect");
    McpServerConnection connection =
        new McpServerConnection(
            McpReferenceServerSupport.server(temp, "docs", "slow-reconnect", ready.toString()),
            McpReferenceServerSupport.settings(temp, 1_048_576, 1));
    Tool echo =
        connection.tools().stream()
            .filter(item -> item.definition().name().equals("mcp_docs__echo"))
            .findFirst()
            .orElseThrow();
    assertThat(echo.execute(Map.of("text", "first-fails")).status())
        .isEqualTo(ToolResultStatus.ERROR);
    Files.createFile(ready);

    AtomicReference<io.namei.agent.kernel.tool.ToolResult> result = new AtomicReference<>();
    Thread reconnecting =
        Thread.ofVirtual().start(() -> result.set(echo.execute(Map.of("text", "racing-call"))));
    awaitState(connection, McpServerConnection.State.RECONNECTING);

    connection.close();
    reconnecting.join(3_000);

    assertThat(reconnecting.isAlive()).isFalse();
    assertThat(result.get().status()).isEqualTo(ToolResultStatus.ERROR);
    assertThat(connection.state()).isEqualTo(McpServerConnection.State.CLOSED);
    Thread.sleep(750);
    assertThat(connection.state()).isEqualTo(McpServerConnection.State.CLOSED);
  }

  private McpRuntime runtime(McpServerDefinition server) {
    return McpRuntimes.staticReadOnly(
        new McpConfiguration(1, List.of(server)),
        McpReferenceServerSupport.settings(temp, 1_048_576, 1));
  }

  private static Tool tool(McpRuntime runtime, String name) {
    return runtime.tools().stream()
        .filter(item -> item.definition().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static void awaitState(McpServerConnection connection, McpServerConnection.State expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (connection.state() != expected && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(connection.state()).isEqualTo(expected);
  }
}
