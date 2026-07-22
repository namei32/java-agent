package io.namei.agent.application;

import io.namei.agent.kernel.port.ProactiveJobInspectionPort;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.proactive.ProactiveJobInspectionSnapshot;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 对已激活本地 Runtime 中 Hash 安全 Active Job 的 Deferred 只读检视。 */
public final class ProactiveJobInspectionToolset {
  static final String VERSION = "local-proactive-inspect-v1";
  static final int DEFAULT_LIMIT = 16;
  static final int MAX_LIMIT = 32;
  public static final int MAX_PROJECTED_CHARACTERS = 8_192;
  static final String INVALID_ARGUMENT = "PROACTIVE_JOB_INSPECTION_INVALID_ARGUMENT";
  static final String UNAVAILABLE = "PROACTIVE_JOB_INSPECTION_UNAVAILABLE";
  private static final ProactiveJobInspectionToolset DISABLED =
      new ProactiveJobInspectionToolset(List.of());

  private final List<Tool> tools;

  private ProactiveJobInspectionToolset(List<Tool> tools) {
    this.tools = List.copyOf(tools);
  }

  public static ProactiveJobInspectionToolset disabled() {
    return DISABLED;
  }

  public static ProactiveJobInspectionToolset enabled(ProactiveJobInspectionPort port) {
    return new ProactiveJobInspectionToolset(List.of(new ListLocalProactiveJobsTool(port)));
  }

  public List<Tool> tools() {
    return tools;
  }

  private static final class ListLocalProactiveJobsTool implements Tool {
    private static final ToolDefinition DEFINITION =
        new ToolDefinition(
            "list_local_proactive_jobs",
            "查看当前本地主动运行时中待执行任务的安全摘要。",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("limit", Map.of("type", "integer")),
                "additionalProperties",
                false),
            ToolRisk.READ_ONLY,
            VERSION);

    private final ProactiveJobInspectionPort port;

    private ListLocalProactiveJobsTool(ProactiveJobInspectionPort port) {
      this.port = Objects.requireNonNull(port, "port");
    }

    @Override
    public ToolDefinition definition() {
      return DEFINITION;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
      InspectionRequest request;
      try {
        request = parse(arguments);
      } catch (IllegalArgumentException invalid) {
        return ToolResult.error(INVALID_ARGUMENT);
      }
      try {
        List<ProactiveJobInspectionSnapshot> snapshots = port.listActive(request.limit());
        validateSnapshots(snapshots, request.limit());
        return ToolResult.success(render(snapshots, request.limit()));
      } catch (RuntimeException unavailable) {
        return ToolResult.error(UNAVAILABLE);
      }
    }
  }

  private static InspectionRequest parse(Map<String, Object> arguments) {
    if (arguments == null || !Set.of("limit").containsAll(arguments.keySet())) {
      throw new IllegalArgumentException("参数无效");
    }
    return new InspectionRequest(integer(arguments.get("limit"), DEFAULT_LIMIT, 1, MAX_LIMIT));
  }

  private static int integer(Object raw, int defaultValue, int minimum, int maximum) {
    if (raw == null) {
      return defaultValue;
    }
    long value;
    if (raw instanceof Byte
        || raw instanceof Short
        || raw instanceof Integer
        || raw instanceof Long) {
      value = ((Number) raw).longValue();
    } else if (raw instanceof BigInteger integer) {
      value = integer.longValueExact();
    } else if (raw instanceof BigDecimal decimal) {
      if (decimal.scale() != 0) {
        throw new IllegalArgumentException("数值必须是整数");
      }
      value = decimal.longValueExact();
    } else {
      throw new IllegalArgumentException("数值必须是整数");
    }
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException("数值超出范围");
    }
    return Math.toIntExact(value);
  }

  private static void validateSnapshots(List<ProactiveJobInspectionSnapshot> snapshots, int limit) {
    if (snapshots == null || snapshots.size() > limit) {
      throw new IllegalStateException("Proactive inspection 结果超过边界");
    }
    var ordering =
        Comparator.comparing(
                (ProactiveJobInspectionSnapshot snapshot) -> snapshot.schedule().nextRunAt())
            .thenComparing(snapshot -> snapshot.jobRef().value());
    ProactiveJobInspectionSnapshot previous = null;
    for (ProactiveJobInspectionSnapshot snapshot : snapshots) {
      if (snapshot == null
          || snapshot.state().terminal()
          || (snapshot.schedule().every() != null
              && !wholePositiveSeconds(snapshot.schedule().every()))
          || (previous != null && ordering.compare(previous, snapshot) > 0)) {
        throw new IllegalStateException("Proactive inspection 结果不安全");
      }
      previous = snapshot;
    }
  }

  private static boolean wholePositiveSeconds(Duration duration) {
    return duration.toMillis() > 0 && duration.toMillis() % 1_000 == 0;
  }

  private static String render(List<ProactiveJobInspectionSnapshot> snapshots, int limit) {
    var output =
        new StringBuilder("{\"count\":")
            .append(snapshots.size())
            .append(",\"limit\":")
            .append(limit)
            .append(",\"jobs\":[");
    for (int index = 0; index < snapshots.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      ProactiveJobInspectionSnapshot snapshot = snapshots.get(index);
      output
          .append("{\"job_ref\":")
          .append(ToolCatalogJson.string(snapshot.jobRef().value()))
          .append(",\"schedule\":")
          .append(ToolCatalogJson.string(snapshot.schedule().kind().name()))
          .append(",\"next_run_at\":")
          .append(ToolCatalogJson.string(snapshot.schedule().nextRunAt().toString()));
      if (snapshot.schedule().every() != null) {
        output.append(",\"every_seconds\":").append(snapshot.schedule().every().toSeconds());
      }
      output
          .append(",\"state\":")
          .append(ToolCatalogJson.string(snapshot.state().name()))
          .append(",\"attempts\":")
          .append(snapshot.attempts())
          .append(",\"max_attempts\":")
          .append(snapshot.maxAttempts())
          .append('}');
    }
    String rendered = output.append("]}").toString();
    if (rendered.codePointCount(0, rendered.length()) > MAX_PROJECTED_CHARACTERS) {
      throw new IllegalStateException("Proactive inspection 投影超过预算");
    }
    return rendered;
  }

  private record InspectionRequest(int limit) {}
}
