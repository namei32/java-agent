package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.ArrayDeque;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolAdapterTest {
  private static final ToolDefinition DEFINITION =
      new ToolDefinition(
          "mcp_docs__echo",
          "MCP echo",
          Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
          ToolRisk.READ_ONLY);

  @Test
  void mapsOnlyBoundedSafeOutcomesToKernelResults() {
    var invoker =
        new StubInvoker(
            McpCallOutcome.success("结果"),
            McpCallOutcome.remoteError(),
            McpCallOutcome.unsupportedResult(),
            McpCallOutcome.timeout(),
            McpCallOutcome.unavailable());
    var tool = new McpToolAdapter(new McpProjectedTool("echo", DEFINITION), invoker);

    assertThat(tool.definition()).isSameAs(DEFINITION);
    assertThat(tool.definition().risk()).isEqualTo(ToolRisk.READ_ONLY);
    assertThat(tool.execute(Map.of()).content()).isEqualTo("结果");
    assertThat(tool.execute(Map.of()).content()).isEqualTo("MCP 工具执行失败。").doesNotContain("private");
    assertThat(tool.execute(Map.of()).content()).isEqualTo("MCP 工具结果类型不受支持。");
    assertThat(tool.execute(Map.of()).status()).isEqualTo(ToolResultStatus.TIMEOUT);
    assertThat(tool.execute(Map.of()).content()).isEqualTo("MCP 工具不可用。");
  }

  @Test
  void failsClosedWhenServerIsStaleAndConvertsCancellation() {
    McpToolInvoker stale =
        new McpToolInvoker() {
          @Override
          public boolean available() {
            return false;
          }

          @Override
          public McpCallOutcome call(String remoteName, Map<String, Object> arguments) {
            throw new AssertionError("STALE Wrapper 不得调用 Server");
          }
        };
    assertThat(
            new McpToolAdapter(new McpProjectedTool("echo", DEFINITION), stale).execute(Map.of()))
        .extracting(result -> result.status(), result -> result.content())
        .containsExactly(ToolResultStatus.ERROR, "MCP 工具不可用。");

    McpToolInvoker cancelled =
        new McpToolInvoker() {
          @Override
          public boolean available() {
            return true;
          }

          @Override
          public McpCallOutcome call(String remoteName, Map<String, Object> arguments) {
            throw new McpCallCancelledException();
          }
        };
    assertThat(
            new McpToolAdapter(new McpProjectedTool("echo", DEFINITION), cancelled)
                .execute(Map.of()))
        .extracting(result -> result.status(), result -> result.content())
        .containsExactly(ToolResultStatus.CANCELLED, "工具调用已取消。");
  }

  private static final class StubInvoker implements McpToolInvoker {
    private final ArrayDeque<McpCallOutcome> outcomes;

    private StubInvoker(McpCallOutcome... outcomes) {
      this.outcomes = new ArrayDeque<>(java.util.List.of(outcomes));
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public McpCallOutcome call(String remoteName, Map<String, Object> arguments) {
      return outcomes.removeFirst();
    }
  }
}
