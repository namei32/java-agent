package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolProjectorTest {
  @Test
  void projectsOnlyLocallyAllowlistedReadOnlyToolAndIgnoresRemoteRiskAnnotation() {
    McpServerDefinition server =
        server(Map.of("search", new McpToolPolicy(true, ToolRisk.READ_ONLY)));
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("query", Map.of("type", "string"), "limit", Map.of("type", "integer")),
            "required",
            List.of("query"));
    McpRemoteTool remote =
        new McpRemoteTool("search", " Search public documentation ", schema, false);

    McpProjectedTool projected = new McpToolProjector(65_536).project(server, remote).orElseThrow();

    assertThat(projected.remoteName()).isEqualTo("search");
    assertThat(projected.definition().name()).isEqualTo("mcp_docs__search");
    assertThat(projected.definition().description()).isEqualTo("Search public documentation");
    assertThat(projected.definition().risk()).isEqualTo(ToolRisk.READ_ONLY);
    assertThat(projected.definition().inputSchema())
        .containsEntry("type", "object")
        .containsEntry("additionalProperties", false);
  }

  @Test
  void normalizesMissingParameterSchemaToClosedEmptyObject() {
    McpProjectedTool projected =
        new McpToolProjector(65_536)
            .project(
                server(Map.of("status", new McpToolPolicy(true, ToolRisk.READ_ONLY))),
                new McpRemoteTool("status", "Return status", null, true))
            .orElseThrow();

    assertThat(projected.definition().inputSchema())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));
  }

  @Test
  void refusesToolsOutsideAllowlistOrDisabledByOperator() {
    McpToolProjector projector = new McpToolProjector(65_536);
    McpRemoteTool remote =
        new McpRemoteTool(
            "search", "Search", Map.of("type", "object", "additionalProperties", false), true);

    assertThat(projector.project(server(Map.of()), remote)).isEmpty();
    assertThat(
            projector.project(
                server(Map.of("search", new McpToolPolicy(false, ToolRisk.READ_ONLY))), remote))
        .isEmpty();
  }

  @Test
  void isolatesUnsupportedUnboundedOrOversizedSchemaAndDescription() {
    McpToolProjector projector = new McpToolProjector(256);
    McpServerDefinition server =
        server(Map.of("unsafe", new McpToolPolicy(true, ToolRisk.READ_ONLY)));
    List<Map<String, Object>> schemas =
        List.of(
            Map.of("type", "object", "$ref", "https://example.invalid/schema"),
            Map.of("type", "array"),
            Map.of("type", "object", "additionalProperties", true),
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string", "format", "uri"))),
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string")),
                "required",
                List.of("missing")),
            deeplyNestedSchema(20),
            manyPropertiesSchema(129),
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string", "enum", List.of()))));

    for (Map<String, Object> schema : schemas) {
      assertThat(
              projector.project(server, new McpRemoteTool("unsafe", "Unsafe schema", schema, true)))
          .isEmpty();
    }
    assertThat(
            projector.project(
                server,
                new McpRemoteTool(
                    "unsafe",
                    "x".repeat(McpToolProjector.MAX_DESCRIPTION_BYTES + 1),
                    Map.of("type", "object"),
                    true)))
        .isEmpty();
    assertThat(
            new McpToolProjector(32)
                .project(
                    server,
                    new McpRemoteTool(
                        "unsafe",
                        "Unsafe schema",
                        Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("value", Map.of("type", "string"))),
                        true)))
        .isEmpty();
  }

  @Test
  void boundsCatalogPagesCursorsCountsAndDuplicateRemoteNames() {
    McpRemoteTool first = tool("first");
    McpRemoteTool second = tool("second");
    McpCatalogAccumulator catalog = new McpCatalogAccumulator(2, 2);

    catalog.addPage(null, "page-2", List.of(first));
    catalog.addPage("page-2", null, List.of(second));

    assertThat(catalog.finish()).containsExactly(first, second);
    assertThatThrownBy(() -> catalog.addPage(null, null, List.of()))
        .isInstanceOf(McpCatalogException.class);

    McpCatalogAccumulator duplicate = new McpCatalogAccumulator(2, 2);
    duplicate.addPage(null, "page-2", List.of(first));
    assertThatThrownBy(() -> duplicate.addPage("page-2", null, List.of(tool("first"))))
        .isInstanceOf(McpCatalogException.class);

    McpCatalogAccumulator repeatedCursor = new McpCatalogAccumulator(3, 3);
    repeatedCursor.addPage(null, "page-2", List.of(first));
    assertThatThrownBy(() -> repeatedCursor.addPage("page-2", "page-2", List.of(second)))
        .isInstanceOf(McpCatalogException.class);

    McpCatalogAccumulator tooManyPages = new McpCatalogAccumulator(1, 2);
    tooManyPages.addPage(null, "page-2", List.of(first));
    assertThatThrownBy(() -> tooManyPages.addPage("page-2", null, List.of(second)))
        .isInstanceOf(McpCatalogException.class);
  }

  private static McpRemoteTool tool(String name) {
    return new McpRemoteTool(
        name, "Description " + name, Map.of("type", "object", "additionalProperties", false), true);
  }

  private static McpServerDefinition server(Map<String, McpToolPolicy> tools) {
    return new McpServerDefinition(
        "docs", Path.of("/test/java"), List.of(), Path.of("/test/runtime"), List.of(), tools);
  }

  private static Map<String, Object> deeplyNestedSchema(int depth) {
    Map<String, Object> current = Map.of("type", "string");
    for (int index = 0; index < depth; index++) {
      Map<String, Object> next = new LinkedHashMap<>();
      next.put("type", "object");
      next.put("properties", Map.of("nested", current));
      next.put("additionalProperties", false);
      current = next;
    }
    return current;
  }

  private static Map<String, Object> manyPropertiesSchema(int count) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (int index = 0; index < count; index++) {
      properties.put("property_" + index, Map.of("type", "string"));
    }
    return Map.of("type", "object", "properties", properties, "additionalProperties", false);
  }
}
