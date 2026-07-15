package io.namei.agent.kernel.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class GoldenManifestTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void verifiesManifestHashesAndCaseIdentifiers() throws Exception {
    Path root = goldenRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json"));

    assertThat(manifest.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(manifest.path("pythonBaseline").path("commit").asString()).matches("[0-9a-f]{40}");

    var fixtureIds = new HashSet<String>();
    for (JsonNode entry : manifest.path("fixtures")) {
      String id = requiredText(entry, "id");
      assertThat(fixtureIds.add(id)).as("重复 Fixture ID: %s", id).isTrue();
      String source = requiredText(entry, "source");
      assertThat(source).isIn("python-reference", "migration-contract", "java-contract");

      Path fixture = root.resolve(requiredText(entry, "path")).normalize();
      assertThat(fixture).as("夹具必须位于 Golden 根目录内").startsWith(root.normalize());
      assertThat(Files.isRegularFile(fixture)).as("夹具不存在: %s", fixture).isTrue();
      assertThat(sha256(fixture)).isEqualTo(requiredText(entry, "sha256"));

      JsonNode document = JSON.readTree(fixture);
      assertThat(document.path("formatVersion").asInt()).isEqualTo(1);
      assertThat(document.path("suite").asString()).isNotBlank();
      assertThat(document.path("source").asString()).isEqualTo(source);
      assertEvidence(document, source, fixture);
      assertFixtureCases(document, fixture);
    }
  }

  private static void assertFixtureCases(JsonNode document, Path fixture) {
    if (document.path("suite").asString().equals("provider-streaming-cli")) {
      assertThat(document.path("normalization").isMissingNode())
          .as("Provider Streaming Fixture 不使用跨语言 normalization: %s", fixture)
          .isTrue();
      assertThat(document.path("limits").isObject())
          .as("Provider Streaming Fixture 必须固定 limits: %s", fixture)
          .isTrue();
      assertUniqueGroupedCaseIds(document, fixture, "kernelCases", "applicationCases", "cliCases");
      return;
    }

    assertThat(document.path("normalization").isArray()).isTrue();
    assertUniqueCaseIds(document, fixture);
  }

  private static void assertEvidence(JsonNode document, String source, Path fixture) {
    if (source.equals("java-contract")) {
      assertThat(document.path("pythonEvidence").isMissingNode())
          .as("Java Contract Fixture 不得声明 Python Evidence: %s", fixture)
          .isTrue();
      JsonNode evidence = document.path("contractEvidence");
      assertThat(evidence.isObject()).as("缺少 Contract Evidence: %s", fixture).isTrue();
      assertThat(requiredText(evidence, "approvedOn")).matches("\\d{4}-\\d{2}-\\d{2}");
      assertThat(requiredText(evidence, "adr")).endsWith(".md");
      assertThat(requiredText(evidence, "contract")).endsWith(".md");
      assertThat(requiredText(evidence, "spec")).endsWith(".md");
      return;
    }

    assertThat(document.path("pythonEvidence").isMissingNode())
        .as("缺少 Python Evidence: %s", fixture)
        .isFalse();
    assertThat(document.path("contractEvidence").isMissingNode())
        .as("非 Java Contract Fixture 不得声明 Contract Evidence: %s", fixture)
        .isTrue();
  }

  private static void assertUniqueCaseIds(JsonNode document, Path fixture) {
    assertThat(document.path("cases").isArray()).as("cases 必须是数组: %s", fixture).isTrue();
    assertThat(document.path("cases").isEmpty()).as("cases 不能为空: %s", fixture).isFalse();
    var caseIds = new HashSet<String>();
    for (JsonNode testCase : document.path("cases")) {
      String id = requiredText(testCase, "id");
      assertThat(caseIds.add(id)).as("重复 Case ID: %s#%s", fixture, id).isTrue();
      assertThat(
              testCase.path("input").isMissingNode() && testCase.path("javaAppend").isMissingNode())
          .as("Case 必须包含 input 或 javaAppend: %s#%s", fixture, id)
          .isFalse();
    }
  }

  private static void assertUniqueGroupedCaseIds(
      JsonNode document, Path fixture, String... groupNames) {
    var caseIds = new HashSet<String>();
    for (String groupName : groupNames) {
      JsonNode cases = document.path(groupName);
      assertThat(cases.isArray()).as("%s 必须是数组: %s", groupName, fixture).isTrue();
      assertThat(cases.isEmpty()).as("%s 不能为空: %s", groupName, fixture).isFalse();
      for (JsonNode testCase : cases) {
        String id = requiredText(testCase, "id");
        assertThat(caseIds.add(id)).as("重复 Case ID: %s#%s", fixture, id).isTrue();
        assertThat(testCase.path("input").isMissingNode())
            .as("Case 必须包含 input: %s#%s", fixture, id)
            .isFalse();
        assertThat(testCase.path("expected").isMissingNode())
            .as("Case 必须包含 expected: %s#%s", fixture, id)
            .isFalse();
      }
    }
  }

  private static String requiredText(JsonNode node, String field) {
    String value = node.path(field).asString();
    assertThat(value).as("缺少必需字段: %s", field).isNotBlank();
    return value;
  }

  private static String sha256(Path path) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
