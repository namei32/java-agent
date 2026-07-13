package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ConfigurationGoldenFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void selectedParserReadsPythonResolutionFixturesWithoutLosingNativeTypes() throws Exception {
    JsonNode fixture = readFixture("configuration/config-resolution.json");

    assertThat(fixture.path("source").asString()).isEqualTo("python-reference");
    assertThat(fixture.path("cases")).hasSize(7);
    for (JsonNode testCase : fixture.path("cases")) {
      String caseId = testCase.path("id").asString();
      String toml = testCase.path("input").path("toml").asString();
      var parsed = Toml.parse(toml);

      assertThat(parsed.errors()).as("TOML 必须可解析: %s", caseId).isEmpty();
      assertThat(sha256(toml))
          .as("TOML 文本 Hash 必须与 Python 生成器一致: %s", caseId)
          .isEqualTo(testCase.path("input").path("tomlSha256").asString());

      JsonNode active = testCase.path("expected").path("active");
      assertThat(active.path("provider").asString()).isNotBlank();
      assertThat(active.path("model").asString()).isNotBlank();
      assertThat(active.path("apiKeyStatus").asString()).isIn("PRESENT", "MISSING", "UNRESOLVED");
      assertThat(active.path("historyMaxMessages").isIntegralNumber()).isTrue();

      Object memoryWindow = parsed.get("agent.context.memory_window");
      if (memoryWindow != null) {
        assertThat(memoryWindow).as("TOML 整数必须保留原生类型: %s", caseId).isInstanceOf(Long.class);
      }
    }
  }

  @Test
  void selectedParserDistinguishesSyntaxErrorsFromContractValidationErrors() throws Exception {
    JsonNode fixture = readFixture("configuration/config-validation.json");

    assertThat(fixture.path("source").asString()).isEqualTo("migration-contract");
    assertThat(fixture.path("cases")).hasSize(5);
    for (JsonNode testCase : fixture.path("cases")) {
      String caseId = testCase.path("id").asString();
      var parsed = Toml.parse(testCase.path("input").path("toml").asString());
      boolean expectedValid =
          testCase.path("expected").path("tomlSyntax").asString().equals("VALID");

      assertThat(parsed.errors().isEmpty())
          .as("Parser 语法判断必须符合契约夹具: %s", caseId)
          .isEqualTo(expectedValid);
      assertThat(testCase.path("expected").path("diagnostics").isEmpty()).isFalse();
    }
  }

  private static JsonNode readFixture(String relativePath) throws Exception {
    return JSON.readTree(goldenRoot().resolve(relativePath));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
