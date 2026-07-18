package io.namei.agent.adapter.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.namei.agent.kernel.port.Tool;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class WorkspaceToolContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executesEveryVersionedReadOnlyWorkspaceToolCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/read-only-workspace-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("tools/read-only-workspace-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(14);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase);
    }
  }

  private static void verify(JsonNode testCase) {
    String id = testCase.path("id").asString();
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    try {
      switch (testCase.path("group").asString()) {
        case "mode" -> WorkspaceToolMode.parse(input.path("mode").asString());
        case "toolset" ->
            assertThat(WorkspaceReadOnlyToolset.disabled().tools())
                .hasSize(expected.path("tools").asInt());
        case "definition" -> definition(input.path("name").asString(), expected);
        case "path" -> WorkspaceToolPath.parse(input.path("path").asString());
        case "limits" -> limits(expected);
        case "error" -> WorkspaceToolError.parse(input.path("code").asString());
        default -> throw new AssertionError("未知 Workspace Tool Fixture 分组: " + id);
      }
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + id);
      }
    } catch (WorkspaceToolContractException violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + id, violation);
      }
      assertThat(violation.code().name()).as(id).isEqualTo(expected.path("code").asString());
    }
  }

  private static void definition(String name, JsonNode expected) {
    WorkspaceToolLimits limits = WorkspaceToolLimits.defaults();
    Tool tool =
        switch (name) {
          case "read_file" -> new ReadWorkspaceFileTool(Path.of("/tmp"), limits);
          case "list_dir" -> new ListWorkspaceDirectoryTool(Path.of("/tmp"), limits);
          default -> throw new AssertionError("未知 Tool Definition: " + name);
        };
    assertThat(tool.definition().version()).isEqualTo(expected.path("version").asString());
    assertThat(tool.definition().risk().name()).isEqualTo(expected.path("risk").asString());
  }

  private static void limits(JsonNode expected) {
    WorkspaceToolLimits limits = WorkspaceToolLimits.defaults();
    if (expected.has("maxLines")) {
      assertThat(limits.maxLines()).isEqualTo(expected.path("maxLines").asInt());
      assertThat(limits.maxOutputBytes()).isEqualTo(expected.path("maxOutputBytes").asInt());
      assertThat(limits.maxOutputCodePoints())
          .isEqualTo(expected.path("maxOutputCodePoints").asInt());
    } else {
      assertThat(limits.maxDirectoryEntries()).isEqualTo(expected.path("maxEntries").asInt());
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
