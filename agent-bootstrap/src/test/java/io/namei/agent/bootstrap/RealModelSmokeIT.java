package io.namei.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.RestClient;

@Tag("real-model")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealModelSmokeIT {
  private static final Path WORKSPACE = createWorkspace();

  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("agent.workspace", WORKSPACE::toString);
    registry.add("spring.ai.openai.base-url", () -> required("OPENAI_BASE_URL"));
    registry.add("spring.ai.openai.api-key", () -> required("OPENAI_API_KEY"));
    registry.add("spring.ai.openai.chat.model", () -> required("OPENAI_MODEL"));
  }

  @AfterAll
  static void cleanUp() {
    FileSystemUtils.deleteRecursively(WORKSPACE.toFile());
  }

  @Test
  void returnsOneNonEmptyAssistantMessage() {
    String body =
        RestClient.create("http://127.0.0.1:" + port)
            .post()
            .uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"sessionId\":\"real-smoke\",\"message\":\"只回复 pong\"}")
            .retrieve()
            .body(String.class);

    assertThat(body)
        .contains("\"role\":\"assistant\"", "\"content\":")
        .doesNotContain("\"content\":\"\"");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("缺少环境变量: " + name);
    }
    return value;
  }

  private static Path createWorkspace() {
    try {
      return Files.createTempDirectory("namei-real-smoke-");
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
