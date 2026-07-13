package io.namei.agent.bootstrap.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ActuatorExposureIT.FakeModelConfiguration.class)
class ActuatorExposureIT {
  private static final Path WORKSPACE = createWorkspace();

  @LocalServerPort int port;
  @Autowired CountingModel model;

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
  void exposesOnlyHealthWithoutCallingModel() {
    RestClient client = RestClient.create("http://127.0.0.1:" + port);

    assertThat(
            client
                .get()
                .uri("/actuator/health")
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThatThrownBy(() -> client.get().uri("/actuator/env").retrieve().toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThat(model.calls.get()).isZero();
  }

  private static Path createWorkspace() {
    try {
      return Files.createTempDirectory("namei-health-");
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeModelConfiguration {
    @Bean
    @Primary
    CountingModel countingModel() {
      return new CountingModel();
    }
  }

  static final class CountingModel implements ChatModelPort {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      calls.incrementAndGet();
      return new ChatModelResponse("unused");
    }
  }
}
