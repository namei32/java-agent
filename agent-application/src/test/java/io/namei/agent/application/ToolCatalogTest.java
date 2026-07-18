package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCatalogTest {
  @Test
  void exposesOnlyAlwaysOnEntriesUntilTheCurrentTurnSearches() {
    ToolCatalog catalog =
        new ToolCatalog(
            List.of(
                entry("current_time", "返回当前 UTC 时间", ToolCatalogVisibility.ALWAYS_ON),
                entry("calendar_lookup", "查询日历事件", ToolCatalogVisibility.DEFERRED),
                entry("health_snapshot", "读取健康数据", ToolCatalogVisibility.DEFERRED)));

    ToolCatalogSession session = catalog.newSession();

    assertThat(session.visibleNames()).containsExactly("current_time", "tool_search");
    assertThat(catalog.search(session, "日历", 5).unlocked()).containsExactly("calendar_lookup");
    assertThat(session.visibleNames())
        .containsExactly("current_time", "tool_search", "calendar_lookup");
    assertThat(catalog.search(session, "健康数据", 5).unlocked()).containsExactly("health_snapshot");

    assertThat(catalog.newSession().visibleNames()).containsExactly("current_time", "tool_search");
  }

  @Test
  void ranksMatchesDeterministicallyAndSupportsExactSelection() {
    ToolCatalog catalog =
        new ToolCatalog(
            List.of(
                entry("calendar_create", "创建 calendar event 日历事件", ToolCatalogVisibility.DEFERRED),
                entry("calendar_lookup", "查询 calendar event 日历事件", ToolCatalogVisibility.DEFERRED),
                entry("weather_lookup", "查询天气", ToolCatalogVisibility.DEFERRED)));
    ToolCatalogSession session = catalog.newSession();

    ToolCatalogSearchResult searched = catalog.search(session, "calendar event", 5);

    assertThat(searched.matched())
        .extracting(ToolCatalogMatch::name)
        .containsExactly("calendar_create", "calendar_lookup");
    assertThat(catalog.search(session, "select:weather_lookup,missing", 5).unlocked())
        .containsExactly("weather_lookup");
    assertThat(catalog.search(session, "select:weather_lookup", 5).alreadyLoaded())
        .containsExactly("weather_lookup");
  }

  @Test
  void rejectsReservedOrInvalidCatalogMetadataAtConstruction() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ToolCatalog(
                    List.of(entry("tool_search", "模型提供的同名工具", ToolCatalogVisibility.ALWAYS_ON))))
        .withMessage("工具名称为保留名称: tool_search");

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ToolCatalogEntry(
                    stub("calendar_lookup", "查询日历事件"),
                    ToolCatalogVisibility.DEFERRED,
                    ToolCatalogSource.MCP,
                    "",
                    List.of("日历")))
        .withMessage("MCP 来源必须提供来源标识");
  }

  private static ToolCatalogEntry entry(
      String name, String description, ToolCatalogVisibility visibility) {
    return new ToolCatalogEntry(
        stub(name, description), visibility, ToolCatalogSource.BUILTIN, "", List.of());
  }

  private static Tool stub(String name, String description) {
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
