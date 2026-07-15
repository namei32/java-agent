package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpToolNameMapperTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void matchesAllGoldenNameCases() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("mcp/java-mcp-client.json").toFile());
    Map<String, String> expectedByCase = new LinkedHashMap<>();
    for (JsonNode testCase : fixture.path("cases")) {
      if (testCase.path("id").asString().startsWith("name-")
          && testCase.path("input").has("remoteName")) {
        expectedByCase.put(
            testCase.path("id").asString(),
            McpToolNameMapper.map(
                testCase.path("input").path("serverId").asString(),
                testCase.path("input").path("remoteName").asString()));
        assertThat(expectedByCase.get(testCase.path("id").asString()))
            .isEqualTo(testCase.path("expected").path("localName").asString());
      }
    }

    assertThat(expectedByCase.keySet())
        .containsExactly(
            "name-ordinary",
            "name-dot-normalization",
            "name-unicode-normalization",
            "name-truncation");
  }

  @Test
  void mappingIsStableAcrossDiscoveryOrderAndServerNamespaces() {
    Map<String, String> first =
        Map.of(
            "docs/search.docs", McpToolNameMapper.map("docs", "search.docs"),
            "docs/search_docs", McpToolNameMapper.map("docs", "search_docs"),
            "other/search", McpToolNameMapper.map("other", "search"));
    Map<String, String> second =
        Map.of(
            "other/search", McpToolNameMapper.map("other", "search"),
            "docs/search_docs", McpToolNameMapper.map("docs", "search_docs"),
            "docs/search.docs", McpToolNameMapper.map("docs", "search.docs"));

    assertThat(first).isEqualTo(second);
    assertThat(first.values()).doesNotHaveDuplicates();
    assertThat(first)
        .containsEntry("docs/search.docs", "mcp_docs__search_docs_65863dec2f")
        .containsEntry("docs/search_docs", "mcp_docs__search_docs")
        .containsEntry("other/search", "mcp_other__search");
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
