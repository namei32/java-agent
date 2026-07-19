package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class MemoryForgetPendingToolsetFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void fixesTheStaticPendingProducerSchemaAndSafeProjectionFromItsFixture() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/memory-forget-pending-producer-v1.json"));

    assertThat(fixture.path("suite").asText()).isEqualTo("tools/memory-forget-pending-producer-v1");
    assertThat(fixture.path("cases").size()).isEqualTo(13);
    assertThat(MemoryForgetPendingToolset.disabled().tools()).isEmpty();
    assertThat(MemoryForgetPendingToolset.definition().name()).isEqualTo("forget_memory");
    assertThat(MemoryForgetPendingToolset.definition().version())
        .isEqualTo(fixture.path("defaults").path("version").asText());
    assertThat(MemoryForgetPendingToolset.definition().risk()).isEqualTo(ToolRisk.WRITE);
    assertThat(MemoryForgetPendingToolset.pendingAssistantProjection())
        .isEqualTo(fixture.path("defaults").path("pendingAssistantProjection").asText());
    assertThat(MemoryForgetPendingToolset.definition().inputSchema())
        .containsEntry("required", java.util.List.of("ids"))
        .containsEntry("additionalProperties", false);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
