package io.namei.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.lifecycle.TurnEventType;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

@Tag("real-model")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RealModelSmokeIT.LifecycleTestConfiguration.class)
class RealModelSmokeIT {
  private static final Path WORKSPACE = createWorkspace();

  @LocalServerPort int port;
  @org.springframework.beans.factory.annotation.Autowired RecordingObserver observer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("agent.workspace", WORKSPACE::toString);
    registry.add("spring.ai.openai.base-url", () -> required("OPENAI_BASE_URL"));
    registry.add("spring.ai.openai.api-key", () -> required("OPENAI_API_KEY"));
    registry.add("spring.ai.openai.chat.model", () -> required("OPENAI_MODEL"));
    registry.add("agent.tools.mode", () -> "READ_ONLY");
  }

  @BeforeEach
  void resetObserver() {
    observer.events.clear();
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

  @Test
  void completesCurrentTimeToolRoundTripAndPersistsOnlyFinalTurn() throws Exception {
    String body =
        RestClient.create("http://127.0.0.1:" + port)
            .post()
            .uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"sessionId":"real-tool-smoke","message":"必须先调用 current_time 工具获取当前 UTC 时间；收到工具结果后，只回复该结果。不要自行推测时间。"}
                """)
            .retrieve()
            .body(String.class);

    assertThat(body)
        .contains("\"role\":\"assistant\"", "\"content\":")
        .doesNotContain("\"content\":\"\"");
    assertThat(observer.events)
        .anySatisfy(
            event -> {
              assertThat(event.type()).isEqualTo(TurnEventType.TOOL_CALL_STARTED);
              assertThat(event.toolName()).isEqualTo("current_time");
            })
        .anySatisfy(
            event -> {
              assertThat(event.type()).isEqualTo(TurnEventType.TOOL_CALL_COMPLETED);
              assertThat(event.toolName()).isEqualTo("current_time");
              assertThat(event.status()).isEqualTo("SUCCESS");
            });
    assertThat(observer.events)
        .filteredOn(event -> event.type() == TurnEventType.MODEL_REQUESTED)
        .hasSize(2);
    assertThat(observer.events.getLast().type()).isEqualTo(TurnEventType.TURN_COMMITTED);

    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + WORKSPACE.resolve("sessions.db").toAbsolutePath());
        var statement =
            connection.prepareStatement(
                "SELECT COUNT(*), COUNT(tool_chain) FROM messages WHERE session_key = ?")) {
      statement.setString(1, "real-tool-smoke");
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(2);
        assertThat(rows.getInt(2)).isZero();
      }
    }
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

  @TestConfiguration(proxyBeanMethods = false)
  static class LifecycleTestConfiguration {
    @Bean
    RecordingObserver recordingObserver() {
      return new RecordingObserver();
    }

    @Bean
    @Primary
    TurnLifecycleObserver testLifecycleObserver(RecordingObserver observer) {
      return observer;
    }
  }

  static final class RecordingObserver implements TurnLifecycleObserver {
    private final List<TurnLifecycleEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(TurnLifecycleEvent event) {
      events.add(event);
    }
  }
}
