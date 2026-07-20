package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class TrustedProactiveRequestToolsetFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryR14P5TrustedProactiveCatalogCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/r14-trusted-proactive-catalog-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("tools/r14-trusted-proactive-catalog-v1");
    assertThat(fixture.path("cases")).hasSize(9);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase, fixture.path("tools"));
    }
  }

  private static void verify(JsonNode testCase, JsonNode tools) {
    String id = testCase.path("id").asString();
    switch (id) {
      case "disabled-toolset-registers-no-catalog-entries" ->
          assertThat(TrustedProactiveRequestToolset.disabled().tools()).isEmpty();
      case "catalog-only-exposes-exactly-two-static-request-schemas" -> {
        List<ToolDefinition> definitions =
            definitions(TrustedProactiveRequestToolset.catalogOnly().tools());
        assertThat(definitions)
            .extracting(ToolDefinition::name)
            .containsExactly(toolName(tools, 0), toolName(tools, 1));
        assertThat(definitions)
            .extracting(ToolDefinition::version)
            .containsExactly(
                tools.get(0).path("version").asString(), tools.get(1).path("version").asString());
        assertThat(definitions)
            .extracting(ToolDefinition::risk)
            .containsExactly(ToolRisk.WRITE, ToolRisk.EXTERNAL_SIDE_EFFECT);
      }
      case "initial-turn-exposes-only-tool-search" ->
          assertThat(names(catalog().initialDefinitions())).containsExactly("tool_search");
      case "memory-search-unlocks-only-the-memory-request" -> {
        ToolCatalog catalog = catalog();
        ToolCatalogSession session = catalog.newSession();
        assertThat(catalog.search(session, "主动记忆", 5).unlocked())
            .containsExactly("request_proactive_memory_capture");
        assertThat(names(catalog.definitions(session)))
            .containsExactly("tool_search", "request_proactive_memory_capture");
      }
      case "peer-search-unlocks-only-the-peer-request" -> {
        ToolCatalog catalog = catalog();
        ToolCatalogSession session = catalog.newSession();
        assertThat(catalog.search(session, "本地 peer", 5).unlocked())
            .containsExactly("request_local_fake_peer_task");
        assertThat(names(catalog.definitions(session)))
            .containsExactly("tool_search", "request_local_fake_peer_task");
      }
      case "exact-selection-can-unlock-both-static-requests" -> {
        ToolCatalog catalog = catalog();
        ToolCatalogSession session = catalog.newSession();
        assertThat(
                catalog
                    .search(
                        session,
                        "select:request_proactive_memory_capture,request_local_fake_peer_task",
                        5)
                    .unlocked())
            .containsExactly("request_proactive_memory_capture", "request_local_fake_peer_task");
      }
      case "schemas-accept-no-sensitive-or-dynamic-arguments" ->
          definitions(TrustedProactiveRequestToolset.catalogOnly().tools())
              .forEach(
                  definition ->
                      assertThat(definition.inputSchema())
                          .containsEntry("type", "object")
                          .containsEntry("properties", Map.of())
                          .containsEntry("additionalProperties", false)
                          .doesNotContainKeys(
                              "scope",
                              "session",
                              "id",
                              "peer",
                              "url",
                              "command",
                              "path",
                              "approval"));
      case "placeholder-is-fixed-unavailable-and-has-no-side-effect" ->
          TrustedProactiveRequestToolset.catalogOnly()
              .tools()
              .forEach(
                  tool ->
                      assertThat(tool.execute(Map.of("ignored", "value")))
                          .extracting(result -> result.status(), result -> result.content())
                          .containsExactly(ToolResultStatus.ERROR, "工具不可用。"));
      case "trusted-proactive-entries-coexist-with-forget-memory-catalog-entry" -> {
        List<ToolCatalogEntry> entries = new ArrayList<>(entries());
        Tool forget = MemoryForgetPendingToolset.catalogOnly().tools().getFirst();
        entries.add(
            new ToolCatalogEntry(
                forget,
                ToolCatalogVisibility.DEFERRED,
                ToolCatalogSource.BUILTIN,
                "",
                List.of("遗忘记忆")));
        ToolCatalog catalog = new ToolCatalog(entries);
        assertThat(catalog.allDefinitions())
            .extracting(ToolDefinition::name)
            .contains(
                "forget_memory",
                "request_proactive_memory_capture",
                "request_local_fake_peer_task");
      }
      default -> throw new AssertionError("未知 R14 P5 Fixture Case: " + id);
    }
  }

  private static ToolCatalog catalog() {
    return new ToolCatalog(entries());
  }

  private static List<ToolCatalogEntry> entries() {
    return TrustedProactiveRequestToolset.catalogOnly().tools().stream()
        .map(
            tool ->
                new ToolCatalogEntry(
                    tool,
                    ToolCatalogVisibility.DEFERRED,
                    ToolCatalogSource.BUILTIN,
                    "",
                    tool.definition().name().contains("memory")
                        ? List.of("主动记忆")
                        : List.of("本地 peer")))
        .toList();
  }

  private static List<ToolDefinition> definitions(List<Tool> tools) {
    return tools.stream().map(Tool::definition).toList();
  }

  private static List<String> names(List<ToolDefinition> definitions) {
    return definitions.stream().map(ToolDefinition::name).toList();
  }

  private static String toolName(JsonNode tools, int index) {
    return tools.get(index).path("name").asString();
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
