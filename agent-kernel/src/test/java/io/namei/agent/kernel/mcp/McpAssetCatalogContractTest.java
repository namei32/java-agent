package io.namei.agent.kernel.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class McpAssetCatalogContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executesEveryVersionedReadOnlyMcpAssetCatalogCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("mcp/read-only-asset-catalog-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(McpAssetContract.CURRENT_VERSION);
    assertThat(fixture.path("suite").asString()).isEqualTo("mcp/read-only-asset-catalog-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(13);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase);
    }
  }

  @Test
  void diagnosticStringDoesNotEchoUntrustedAssetNameOrDescription() {
    McpAssetDescriptor descriptor =
        new McpAssetDescriptor(
            McpAssetContract.CURRENT_VERSION,
            "docs",
            McpAssetKind.RESOURCE,
            "mcp_docs__resource_abc123",
            "private external asset name",
            "private external asset description",
            true);

    assertThat(descriptor.toString())
        .doesNotContain("private external asset name", "private external asset description");
  }

  private static void verify(JsonNode testCase) {
    String id = testCase.path("id").asString();
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    try {
      switch (testCase.path("group").asString()) {
        case "mode" -> McpAssetCatalogMode.parse(input.path("mode").asString());
        case "descriptor" -> descriptor(input);
        case "catalog" -> catalog(input, expected);
        case "port" ->
            assertThat(McpAssetCatalogPort.disabled().snapshot().descriptors())
                .hasSize(expected.path("assets").asInt());
        default -> throw new AssertionError("未知 MCP Assets Fixture 分组: " + id);
      }
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + id);
      }
    } catch (McpAssetContractViolation violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + id, violation);
      }
      assertThat(violation.code().name()).as(id).isEqualTo(expected.path("code").asString());
    }
  }

  private static void descriptor(JsonNode input) {
    new McpAssetDescriptor(
        input.path("version").asInt(),
        input.path("serverId").asString(),
        McpAssetKind.parse(input.path("kind").asString()),
        input.path("localName").asString(),
        input.path("name").asString(),
        input.hasNonNull("description") ? input.path("description").asString() : null,
        input.path("available").asBoolean());
  }

  private static void catalog(JsonNode input, JsonNode expected) {
    McpAssetDescriptor alpha = asset("alpha", McpAssetKind.RESOURCE, "mcp_alpha__resource_z");
    McpAssetDescriptor docsResource = asset("docs", McpAssetKind.RESOURCE, "mcp_docs__resource_a");
    McpAssetDescriptor docsPrompt = asset("docs", McpAssetKind.PROMPT, "mcp_docs__prompt_a");
    List<McpAssetDescriptor> descriptors =
        input.path("duplicate").asBoolean()
            ? List.of(docsResource, docsResource)
            : List.of(docsPrompt, docsResource, alpha);
    McpAssetCatalog catalog = new McpAssetCatalog(descriptors);
    assertThat(catalog.descriptors())
        .extracting(McpAssetDescriptor::localName)
        .containsExactlyElementsOf(
            expected.path("ordered").valueStream().map(JsonNode::asString).toList());
  }

  private static McpAssetDescriptor asset(String serverId, McpAssetKind kind, String localName) {
    return new McpAssetDescriptor(
        McpAssetContract.CURRENT_VERSION, serverId, kind, localName, "Public asset", null, true);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
