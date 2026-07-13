package io.namei.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PassiveChatEndpointIT.FakeModelConfiguration.class)
class PassiveChatEndpointIT {
  private static final Path WORKSPACE = createWorkspace();

  @LocalServerPort int port;
  @Autowired RecordingModel model;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("agent.workspace", WORKSPACE::toString);
    registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:1/v1");
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("spring.ai.openai.chat.model", () -> "test-model");
  }

  @AfterAll
  static void cleanUp() {
    FileSystemUtils.deleteRecursively(WORKSPACE.toFile());
  }

  @Test
  void completesTwoTurnsAndSuppliesFirstTurnAsHistory() throws Exception {
    RestClient client = RestClient.create("http://127.0.0.1:" + port);

    assertThat(post(client, "第一问")).contains("\"content\":\"第一答\"");
    assertThat(post(client, "第二问")).contains("\"content\":\"第二答\"");

    assertThat(model.requests).hasSize(2);
    assertThat(model.requests.get(1).messages())
        .extracting(ChatMessage::content)
        .containsSubsequence("第一问", "第一答", "第二问");
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + WORKSPACE.resolve("sessions.db").toAbsolutePath());
        var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM messages")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(4);
    }
  }

  private String post(RestClient client, String message) {
    String body = "{\"sessionId\":\"demo\",\"message\":\"" + message + "\"}";
    var response =
        client
            .post()
            .uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    return response.getBody();
  }

  private static Path createWorkspace() {
    try {
      return Files.createTempDirectory("namei-e2e-");
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeModelConfiguration {
    @Bean
    @Primary
    RecordingModel recordingModel() {
      return new RecordingModel();
    }
  }

  static final class RecordingModel implements ChatModelPort {
    private final List<ChatModelRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      String question = request.messages().getLast().content();
      return new ChatModelResponse(question.equals("第一问") ? "第一答" : "第二答");
    }
  }
}
