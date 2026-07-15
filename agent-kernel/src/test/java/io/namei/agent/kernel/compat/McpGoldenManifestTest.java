package io.namei.agent.kernel.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class McpGoldenManifestTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void freezesJavaOwnedReadOnlyMcpContractCases() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("mcp/java-mcp-client.json").toFile());

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("mcp/java-mcp-client");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("pythonEvidence").isMissingNode()).isTrue();
    assertThat(fixture.path("contractEvidence").path("approvedOn").asString())
        .isEqualTo("2026-07-15");
    assertThat(fixture.path("normalization").size()).isGreaterThanOrEqualTo(6);

    Set<String> ids = new HashSet<>();
    for (JsonNode testCase : fixture.path("cases")) {
      assertThat(ids.add(testCase.path("id").asString())).isTrue();
      assertThat(testCase.path("input").isObject()).isTrue();
      assertThat(testCase.path("expected").isObject()).isTrue();
    }

    assertThat(ids)
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "disabled-zero-effects",
                "static-config-v1",
                "config-unknown-field-fails-before-process",
                "initialize-tools-only",
                "paginated-tool-catalog",
                "name-ordinary",
                "name-dot-normalization",
                "name-unicode-normalization",
                "name-truncation",
                "name-normalization-and-server-collision",
                "schema-safe-object-projection",
                "schema-empty-parameters-normalized",
                "schema-unsupported-ref-isolated",
                "remote-annotation-cannot-lower-risk",
                "call-text-result",
                "call-server-error-redacted",
                "call-non-text-rejected",
                "wire-cancellation-and-late-response",
                "wire-limit-before-deserialization",
                "tools-list-changed-fails-closed",
                "bounded-reconnect-with-catalog-fingerprint",
                "bounded-shutdown-no-orphan"));

    assertThat(findCase(fixture, "name-truncation").path("expected").path("localName").asString())
        .hasSize(64);
  }

  private static JsonNode findCase(JsonNode fixture, String id) {
    for (JsonNode testCase : fixture.path("cases")) {
      if (id.equals(testCase.path("id").asString())) {
        return testCase;
      }
    }
    throw new AssertionError("缺少 MCP Contract Case: " + id);
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
