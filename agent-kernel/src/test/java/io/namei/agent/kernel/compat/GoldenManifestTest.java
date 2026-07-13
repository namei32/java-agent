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
      assertThat(requiredText(entry, "source")).isIn("python-reference", "migration-contract");

      Path fixture = root.resolve(requiredText(entry, "path")).normalize();
      assertThat(fixture).as("夹具必须位于 Golden 根目录内").startsWith(root.normalize());
      assertThat(Files.isRegularFile(fixture)).as("夹具不存在: %s", fixture).isTrue();
      assertThat(sha256(fixture)).isEqualTo(requiredText(entry, "sha256"));

      JsonNode document = JSON.readTree(fixture);
      assertThat(document.path("formatVersion").asInt()).isEqualTo(1);
      assertThat(document.path("suite").asString()).isNotBlank();
      assertThat(document.path("source").asString()).isEqualTo(entry.path("source").asString());
      assertThat(document.path("pythonEvidence").isMissingNode()).isFalse();
      assertThat(document.path("normalization").isArray()).isTrue();
      assertUniqueCaseIds(document, fixture);
    }
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
