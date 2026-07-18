package io.namei.agent.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PluginContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryVersionedPluginContractCase() throws Exception {
    JsonNode fixture = fixture();

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(PluginContract.CURRENT_VERSION);
    assertThat(fixture.path("suite").asString()).isEqualTo("plugins/plugin-extension-runtime");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(20);

    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase);
    }
  }

  private static void verify(JsonNode testCase) {
    String caseId = testCase.path("id").asString();
    JsonNode expected = testCase.path("expected");
    try {
      switch (testCase.path("group").asString()) {
        case "manifest" -> PluginContract.validate(manifest(testCase.path("input")));
        case "catalog" -> {
          var manifests = new ArrayList<PluginManifest>();
          for (JsonNode input : testCase.path("input").path("manifests")) {
            manifests.add(manifest(input));
          }
          List<String> ids =
              PluginCatalog.of(manifests).manifests().stream()
                  .map(item -> item.id().value())
                  .toList();
          assertThat(ids).as(caseId).containsExactlyElementsOf(strings(expected.path("ids")));
        }
        case "stable-code" ->
            assertThat(
                    PluginStableCode.parse(testCase.path("input").path("code").asString())
                        .retryable())
                .as(caseId)
                .isEqualTo(expected.path("retryable").asBoolean());
        default -> throw new AssertionError("未知 Plugin Fixture Group: " + caseId);
      }
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + caseId);
      }
    } catch (PluginContractViolation violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + caseId, violation);
      }
      assertThat(violation.code().name()).as(caseId).isEqualTo(expected.path("code").asString());
    }
  }

  @Test
  void redactsInvalidPluginReferencesFromExceptionMessages() {
    assertThatThrownBy(() -> PluginId.parse("Calendar-Secret"))
        .isInstanceOfSatisfying(
            PluginContractViolation.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(PluginStableCode.PLUGIN_MANIFEST_INVALID);
              assertThat(failure).hasMessageNotContaining("Calendar-Secret");
            });
  }

  private static PluginManifest manifest(JsonNode input) {
    return new PluginManifest(
        input.path("schemaVersion").asInt(),
        PluginId.parse(input.path("id").asString()),
        input.path("version").asString(),
        input.path("apiVersion").asInt(),
        PluginKind.parse(input.path("kind").asString()),
        strings(input.path("capabilities")).stream().map(PluginCapability::parse).toList());
  }

  private static List<String> strings(JsonNode array) {
    var values = new ArrayList<String>();
    for (JsonNode value : array) {
      values.add(value.asString());
    }
    return List.copyOf(values);
  }

  private static JsonNode fixture() throws Exception {
    return JSON.readTree(goldenRoot().resolve("plugins/plugin-extension-runtime-v1.json"));
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
