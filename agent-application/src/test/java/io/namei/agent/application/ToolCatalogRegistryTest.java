package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ToolCatalogRegistryTest {
  @Test
  void unlocksDeferredSchemaOnlyAfterTheSearchCallCompletes() {
    Tool deferred = tool("calendar_lookup", "查询日历 calendar event");
    ToolCatalog catalog =
        new ToolCatalog(
            List.of(
                new ToolCatalogEntry(
                    tool("current_time", "返回当前时间"),
                    ToolCatalogVisibility.ALWAYS_ON,
                    ToolCatalogSource.BUILTIN,
                    "",
                    List.of()),
                new ToolCatalogEntry(
                    deferred,
                    ToolCatalogVisibility.DEFERRED,
                    ToolCatalogSource.MCP,
                    "calendar",
                    List.of("日历"))));
    ToolRegistry registry = new ToolRegistry(catalog, ToolRuntimeSettings.readOnlyDefaults());
    ToolCatalogSession session = registry.newCatalogSession();

    assertThat(registry.definitions(session))
        .extracting(ToolDefinition::name)
        .containsExactly("current_time", "tool_search");
    assertThat(
            registry.prepare(List.of(new ToolCall("hidden", "calendar_lookup", Map.of())), session))
        .singleElement()
        .extracting(ToolRegistry.PreparedCall::preflightFailure)
        .extracting(ToolResult::content)
        .isEqualTo("工具不可用。");

    ToolResult result =
        registry.execute(
            new ToolCall("search", "tool_search", Map.of("query", "calendar event")),
            TurnCancellation.none(),
            session);

    assertThat(result.content()).contains("calendar_lookup").doesNotContain("calendar\"");
    assertThat(registry.definitions(session))
        .extracting(ToolDefinition::name)
        .containsExactly("current_time", "tool_search", "calendar_lookup");
  }

  @Test
  void preservesLegacyListConstructionAsAnAllAlwaysOnCatalog() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(tool("current_time", "返回当前时间")), ToolRuntimeSettings.readOnlyDefaults());

    assertThat(registry.definitions())
        .extracting(ToolDefinition::name)
        .containsExactly("current_time");
  }

  @Test
  @Tag("failure")
  void disabledModeDoesNotExecuteEvenWhenARegistryIsCalledDirectly() {
    var executions = new java.util.concurrent.atomic.AtomicInteger();
    Tool tool =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return new ToolDefinition(
                "current_time",
                "返回当前时间",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                ToolRisk.READ_ONLY);
          }

          @Override
          public ToolResult execute(Map<String, Object> arguments) {
            executions.incrementAndGet();
            return ToolResult.success("不应执行");
          }
        };
    ToolRegistry registry = new ToolRegistry(List.of(tool), ToolRuntimeSettings.disabled());
    ToolCatalogSession session = registry.newCatalogSession();

    assertThat(
            registry
                .prepare(List.of(new ToolCall("call", "current_time", Map.of())), session)
                .getFirst()
                .preflightFailure()
                .content())
        .isEqualTo("工具不可用。");
    assertThat(
            registry
                .execute(
                    new ToolCall("call", "current_time", Map.of()),
                    TurnCancellation.none(),
                    session)
                .content())
        .isEqualTo("工具不可用。");
    assertThat(executions).hasValue(0);
  }

  private static Tool tool(String name, String description) {
    return new Tool() {
      private final ToolDefinition definition =
          new ToolDefinition(
              name,
              description,
              Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
              ToolRisk.READ_ONLY);

      @Override
      public ToolDefinition definition() {
        return definition;
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.success("ok");
      }
    };
  }
}
