package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.List;
import java.util.Map;

/**
 * P5's static, deferred Catalog surface. It provides discovery schemas only: these placeholders
 * cannot create P3/P4 pending work, approvals, capsules, anchors, threads, or port calls.
 */
public final class TrustedProactiveRequestToolset {
  private static final Map<String, Object> EMPTY_INPUT_SCHEMA =
      Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
  private static final ToolDefinition MEMORY_CAPTURE_DEFINITION =
      new ToolDefinition(
          "request_proactive_memory_capture",
          "请求创建经审批的本地主动记忆捕获。",
          EMPTY_INPUT_SCHEMA,
          ToolRisk.WRITE,
          "r14-proactive-memory-v1");
  private static final ToolDefinition LOCAL_FAKE_PEER_DEFINITION =
      new ToolDefinition(
          "request_local_fake_peer_task",
          "请求创建经审批的本地 Fake Peer 固定任务。",
          EMPTY_INPUT_SCHEMA,
          ToolRisk.EXTERNAL_SIDE_EFFECT,
          "r14-local-fake-peer-v1");
  private static final TrustedProactiveRequestToolset DISABLED =
      new TrustedProactiveRequestToolset(List.of());
  private static final TrustedProactiveRequestToolset CATALOG_ONLY =
      new TrustedProactiveRequestToolset(
          List.of(
              new UnavailableRequestTool(MEMORY_CAPTURE_DEFINITION),
              new UnavailableRequestTool(LOCAL_FAKE_PEER_DEFINITION)));

  private final List<Tool> tools;

  private TrustedProactiveRequestToolset(List<Tool> tools) {
    this.tools = List.copyOf(tools);
  }

  /** The production-safe default: no P5 Catalog entry is registered. */
  public static TrustedProactiveRequestToolset disabled() {
    return DISABLED;
  }

  /** Returns static schemas only for Catalog tests; it grants no execution authority. */
  public static TrustedProactiveRequestToolset catalogOnly() {
    return CATALOG_ONLY;
  }

  public static ToolDefinition proactiveMemoryCaptureDefinition() {
    return MEMORY_CAPTURE_DEFINITION;
  }

  public static ToolDefinition localFakePeerTaskDefinition() {
    return LOCAL_FAKE_PEER_DEFINITION;
  }

  public List<Tool> tools() {
    return tools;
  }

  private record UnavailableRequestTool(ToolDefinition definition) implements Tool {
    @Override
    public ToolResult execute(Map<String, Object> arguments) {
      return ToolResult.error("工具不可用。");
    }
  }
}
