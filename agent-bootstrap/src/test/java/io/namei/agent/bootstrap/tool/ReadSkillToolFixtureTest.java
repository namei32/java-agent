package io.namei.agent.bootstrap.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.skill.SkillCatalogPort;
import io.namei.agent.kernel.skill.SkillCatalogSnapshot;
import io.namei.agent.kernel.skill.SkillContent;
import io.namei.agent.kernel.tool.ToolResult;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ReadSkillToolFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executesEveryVersionedReadOnlySkillContentCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("skills/read-only-skill-content-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("skills/read-only-skill-content-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(10);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase);
    }
  }

  private static void verify(JsonNode testCase) {
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    switch (testCase.path("group").asString()) {
      case "definition" -> definition(expected);
      case "execute" -> execute(input, expected);
      default ->
          throw new AssertionError(
              "未知 Skill Content Fixture 分组: " + testCase.path("id").asString());
    }
  }

  private static void definition(JsonNode expected) {
    Tool tool = new ReadSkillTool(SkillCatalogPort.disabled(), 100);

    assertThat(tool.definition().name()).isEqualTo(expected.path("name").asString());
    assertThat(tool.definition().risk().name()).isEqualTo(expected.path("risk").asString());
    assertThat(tool.definition().version()).isEqualTo(expected.path("version").asString());
    assertThat(tool.definition().inputSchema().get("required"))
        .isEqualTo(java.util.List.of("name"));
    assertThat(tool.definition().inputSchema().get("additionalProperties")).isEqualTo(false);
  }

  private static void execute(JsonNode input, JsonNode expected) {
    var tool =
        new ReadSkillTool(
            catalog(input.path("behavior").asString()), input.path("maxReadCodePoints").asInt());
    Map<String, Object> arguments = new java.util.LinkedHashMap<>();
    arguments.put("name", input.path("name").asString());
    if (input.path("extra").asBoolean()) {
      arguments.put("path", "must-not-be-accepted");
    }

    ToolResult result = tool.execute(Map.copyOf(arguments));

    assertThat(result.status().name()).isEqualTo(expected.path("status").asString());
    assertThat(result.content()).isEqualTo(expected.path("content").asString());
  }

  private static SkillCatalogPort catalog(String behavior) {
    return switch (behavior) {
      case "available" ->
          new SkillCatalogPort() {
            @Override
            public SkillCatalogSnapshot snapshot() {
              return SkillCatalogSnapshot.empty();
            }

            @Override
            public Optional<SkillContent> readAvailable(String name) {
              return Optional.of(new SkillContent(name, "Use Chinese."));
            }
          };
      case "missing" -> SkillCatalogPort.disabled();
      case "mismatch" ->
          new SkillCatalogPort() {
            @Override
            public SkillCatalogSnapshot snapshot() {
              return SkillCatalogSnapshot.empty();
            }

            @Override
            public Optional<SkillContent> readAvailable(String name) {
              return Optional.of(new SkillContent("other-skill", "Must not escape."));
            }
          };
      case "disabled" -> SkillCatalogPort.disabled();
      case "failure" ->
          new SkillCatalogPort() {
            @Override
            public SkillCatalogSnapshot snapshot() {
              return SkillCatalogSnapshot.empty();
            }

            @Override
            public Optional<SkillContent> readAvailable(String name) {
              throw new IllegalStateException("path=/secret");
            }
          };
      default -> throw new AssertionError("未知 catalog 行为: " + behavior);
    };
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
