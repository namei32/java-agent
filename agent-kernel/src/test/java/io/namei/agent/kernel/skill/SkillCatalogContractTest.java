package io.namei.agent.kernel.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class SkillCatalogContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executesEveryVersionedReadOnlySkillCatalogCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("skills/read-only-skill-catalog-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(SkillContract.CURRENT_VERSION);
    assertThat(fixture.path("suite").asString()).isEqualTo("skills/read-only-skill-catalog-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(13);

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
        case "descriptor" -> descriptor(input);
        case "source" -> SkillSource.parse(input.path("source").asString());
        case "mode" -> SkillCatalogMode.parse(input.path("mode").asString());
        case "snapshot" -> snapshot(input);
        case "port" -> {
          SkillCatalogSnapshot snapshot = SkillCatalogPort.disabled().snapshot();
          assertThat(snapshot.descriptors()).hasSize(expected.path("descriptors").asInt());
          assertThat(snapshot.activeContents()).hasSize(expected.path("active").asInt());
        }
        default -> throw new AssertionError("未知 Skill Fixture 分组: " + id);
      }
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + id);
      }
    } catch (SkillContractViolation violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + id, violation);
      }
      assertThat(violation.code().name()).as(id).isEqualTo(expected.path("code").asString());
    }
  }

  private static void descriptor(JsonNode input) {
    new SkillDescriptor(
        input.path("name").asString(),
        input.path("description").asString(),
        SkillSource.parse(input.path("source").asString()),
        input.path("available").asBoolean(),
        input.path("always").asBoolean());
  }

  private static void snapshot(JsonNode input) {
    boolean available = input.path("available").asBoolean();
    boolean always = input.path("always").asBoolean();
    SkillDescriptor descriptor =
        new SkillDescriptor("daily-rules", "Daily rules", SkillSource.WORKSPACE, available, always);
    List<SkillDescriptor> descriptors =
        input.path("duplicate").asBoolean() ? List.of(descriptor, descriptor) : List.of(descriptor);
    List<SkillContent> active =
        input.path("active").asBoolean()
            ? List.of(new SkillContent("daily-rules", "Use Chinese."))
            : List.of();
    new SkillCatalogSnapshot(descriptors, active);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
