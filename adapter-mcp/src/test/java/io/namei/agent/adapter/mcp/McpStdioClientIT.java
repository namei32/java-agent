package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpStdioClientIT {
  @TempDir Path temp;

  @Test
  void initializesPaginatesCallsAndClosesWithoutOrphaningTheJavaServer() throws Exception {
    McpStdioClient client =
        new McpStdioClient(
            McpReferenceServerSupport.server(temp, "normal"),
            McpReferenceServerSupport.settings(temp, 1_048_576, 2));
    ProcessHandle child = null;
    try {
      assertThat(client.initializeAndDiscover())
          .extracting(McpRemoteTool::name)
          .containsExactly("echo", "slow", "remote_error", "image", "env_probe");
      child = client.processHandle();
      assertThat(child.isAlive()).isTrue();

      assertThat(client.call("echo", Map.of("text", "你好，MCP 🌱")))
          .isEqualTo(McpCallOutcome.success("你好，MCP 🌱"));
      assertThat(client.call("remote_error", Map.of())).isEqualTo(McpCallOutcome.remoteError());
      assertThat(client.call("remote_error", Map.of()).toString())
          .doesNotContain("private-reference-server-error");
      assertThat(client.call("image", Map.of())).isEqualTo(McpCallOutcome.unsupportedResult());
      assertThat(client.call("env_probe", Map.of()).text())
          .doesNotContain("PATH", "HOME", "PROVIDER_API_KEY", "DOCS_MCP_TOKEN");

      AtomicReference<McpCallOutcome> slow = new AtomicReference<>();
      AtomicReference<McpCallOutcome> fast = new AtomicReference<>();
      Thread first =
          Thread.ofVirtual()
              .start(
                  () -> slow.set(client.call("echo", Map.of("text", "first", "delayMillis", 180))));
      Thread second =
          Thread.ofVirtual()
              .start(
                  () -> fast.set(client.call("echo", Map.of("text", "second", "delayMillis", 10))));
      first.join(3_000);
      second.join(3_000);
      assertThat(first.isAlive()).isFalse();
      assertThat(second.isAlive()).isFalse();
      assertThat(slow).hasValue(McpCallOutcome.success("first"));
      assertThat(fast).hasValue(McpCallOutcome.success("second"));
    } finally {
      client.close();
    }
    assertThat(child).isNotNull();
    assertThat(child.isAlive()).isFalse();
  }

  @Test
  void drainsStderrWithoutDeadlockAndRejectsOversizedStdoutBeforeJsonParsing() {
    try (var client =
        new McpStdioClient(
            McpReferenceServerSupport.server(temp, "stderr-flood"),
            McpReferenceServerSupport.settings(temp, 1_048_576, 1))) {
      assertThat(client.initializeAndDiscover()).isNotEmpty();
    }

    McpStdioClient oversized =
        new McpStdioClient(
            McpReferenceServerSupport.server(temp, "oversized-wire"),
            McpReferenceServerSupport.settings(temp, 16_384, 1));
    ProcessHandle child = null;
    try {
      assertThatThrownBy(oversized::initializeAndDiscover)
          .isInstanceOf(McpClientException.class)
          .hasMessage("MCP Server 不可用。");
      child = oversized.processHandle();
    } finally {
      oversized.close();
    }
    assertThat(child).isNotNull();
    assertThat(child.isAlive()).isFalse();
  }

  @Test
  void escalatesFromTermToKillWithinTheShutdownBound() {
    McpStdioClient client =
        new McpStdioClient(
            McpReferenceServerSupport.server(temp, "kill-on-close"),
            McpReferenceServerSupport.settings(temp, 1_048_576, 1));
    client.initializeAndDiscover();
    ProcessHandle child = client.processHandle();

    long started = System.nanoTime();
    client.close();

    assertThat(DurationSince.millis(started)).isLessThan(2_000);
    assertThat(child.isAlive()).isFalse();
  }

  private static final class DurationSince {
    private DurationSince() {}

    static long millis(long startedNanos) {
      return (System.nanoTime() - startedNanos) / 1_000_000;
    }
  }
}
