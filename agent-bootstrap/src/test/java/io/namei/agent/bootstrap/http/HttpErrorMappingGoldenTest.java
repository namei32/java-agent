package io.namei.agent.bootstrap.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class HttpErrorMappingGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> matchesApprovedPythonFailureToHttpMigrationContract() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("errors/http-error-mapping.json"));
    var tests = new ArrayList<DynamicTest>();

    for (JsonNode testCase : fixture.path("cases")) {
      tests.add(
          DynamicTest.dynamicTest(testCase.path("id").asString(), () -> verifyCase(testCase)));
    }
    return tests.stream();
  }

  private static void verifyCase(JsonNode testCase) {
    JsonNode expected = testCase.path("expected");
    var request = new MockHttpServletRequest();
    request.setRequestURI(expected.path("instance").asString());
    ProblemDetail actual =
        invoke(
            new ApiExceptionHandler(), testCase.path("input").path("scenario").asString(), request);

    assertThat(actual.getType().toString()).isEqualTo(expected.path("type").asString());
    assertThat(actual.getTitle()).isEqualTo(expected.path("title").asString());
    assertThat(actual.getStatus()).isEqualTo(expected.path("status").asInt());
    assertThat(actual.getDetail()).isEqualTo(expected.path("detail").asString());
    assertThat(actual.getInstance().toString()).isEqualTo(expected.path("instance").asString());
  }

  private static ProblemDetail invoke(
      ApiExceptionHandler handler, String scenario, MockHttpServletRequest request) {
    return switch (scenario) {
      case "VALIDATION" -> handler.validation(request);
      case "MALFORMED_JSON" -> handler.malformedJson(request);
      case "MODEL_INVOCATION",
          "INVALID_MODEL_RESPONSE",
          "APPROVAL_UNAVAILABLE",
          "SIDE_EFFECT_STATE_UNKNOWN" ->
          handler.modelFailure(request);
      case "MODEL_TIMEOUT", "SESSION_LOCK_TIMEOUT" -> handler.timeout(request);
      case "SQLITE" -> handler.persistence(request);
      case "NOT_FOUND" -> handler.notFound(request);
      case "UNEXPECTED" -> handler.unexpected(request);
      default -> throw new IllegalArgumentException("未知 Golden 错误场景: " + scenario);
    };
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
