package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ModelStreamLimitExceededException;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.application.TurnCancellation;
import io.namei.agent.bootstrap.NameiAgentApplication;
import io.namei.agent.bootstrap.channel.ChannelHost;
import io.namei.agent.bootstrap.cli.CliInputException;
import io.namei.agent.bootstrap.cli.CliMode;
import io.namei.agent.bootstrap.cli.CliOutputException;
import io.namei.agent.bootstrap.cli.CliProperties;
import io.namei.agent.bootstrap.cli.LocalCliRunner;
import io.namei.agent.bootstrap.cli.Utf8CliInput;
import io.namei.agent.bootstrap.cli.Utf8CliOutput;
import io.namei.agent.bootstrap.telegram.TelegramBotApi;
import io.namei.agent.bootstrap.telegram.TelegramChannelAdapter;
import io.namei.agent.bootstrap.telegram.TelegramSecretSource;
import io.namei.agent.kernel.memory.MemoryRuntimeMode;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.io.ByteArrayResource;

class CliBootstrapTest {
  @TempDir Path tempDir;

  @Test
  void selectsOnlyExplicitCliFlagAndKeepsDefaultWebMode() {
    assertThat(CliMode.isRequested(new String[] {"--cli"})).isTrue();
    assertThat(CliMode.isRequested(new String[] {"--cli=false"})).isFalse();
    assertThat(CliMode.isRequested(new String[0])).isFalse();

    assertThat(NameiAgentApplication.application(new String[] {"--cli"}).getWebApplicationType())
        .isEqualTo(WebApplicationType.NONE);
    assertThat(NameiAgentApplication.application(new String[0]).getWebApplicationType())
        .isEqualTo(WebApplicationType.SERVLET);
  }

  @Test
  void startsRealCliContextWithoutAWebServerAndWiresTrustedDefaults() {
    Path workspace = tempDir.resolve("cli-context");
    String[] args =
        new String[] {
          "--cli",
          "--agent.workspace=" + workspace,
          "--spring.ai.openai.base-url=http://127.0.0.1:1/v1",
          "--spring.ai.openai.api-key=test-key",
          "--spring.ai.openai.chat.model=test-model",
          "--agent.tools.mode=DISABLED",
          "--agent.memory.mode=DISABLED",
          "--agent.mcp.mode=DISABLED",
          "--agent.channels.telegram.enabled=true",
          "--agent.cli.session-id=cli:test-session",
          "--agent.cli.conversation-id=test-conversation"
        };
    var application = NameiAgentApplication.application(args);
    application.setRegisterShutdownHook(false);

    try (var context = application.run(args)) {
      assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
      assertThat(context.getBean(LocalCliRunner.class)).isNotNull();
      assertThat(context.getBean(CliProperties.class).sessionId()).isEqualTo("cli:test-session");
      assertThat(context.getBean(CliProperties.class).conversationId())
          .isEqualTo("test-conversation");
      assertThat(context.getBeansOfType(org.springframework.boot.web.server.WebServer.class))
          .isEmpty();
      assertThat(context.getBeansOfType(ChannelHost.class)).isEmpty();
      assertThat(context.getBeansOfType(TelegramSecretSource.class)).isEmpty();
      assertThat(context.getBeansOfType(TelegramBotApi.class)).isEmpty();
      assertThat(context.getBeansOfType(TelegramChannelAdapter.class)).isEmpty();
      assertThat(Files.isRegularFile(workspace.resolve("sessions.db"))).isTrue();
    }
  }

  @Test
  void readsAndWritesStrictBoundedUtf8WithoutRetainingIoFailures() {
    var input =
        new Utf8CliInput(new ByteArrayInputStream("你好\r\n最后一行".getBytes(StandardCharsets.UTF_8)));
    assertThat(input.readLine()).isEqualTo("你好");
    assertThat(input.readLine()).isEqualTo("最后一行");
    assertThat(input.readLine()).isNull();

    var malformed =
        new Utf8CliInput(new ByteArrayInputStream(new byte[] {(byte) 0xC3, 0x28, '\n'}));
    assertThatThrownBy(malformed::readLine)
        .isInstanceOf(CliInputException.class)
        .hasMessage("CLI stdin 不是有效 UTF-8")
        .hasNoCause();
    var oversized =
        new Utf8CliInput(
            new ByteArrayInputStream(
                "a".repeat(Utf8CliInput.MAX_LINE_BYTES + 1).getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(oversized::readLine)
        .isInstanceOf(CliInputException.class)
        .hasMessage("CLI 输入行超过字节上限");

    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var output = new Utf8CliOutput(stdout, stderr);
    output.writeStdout("回答\n");
    output.writeStderr("MODEL_TIMEOUT\n");
    assertThat(stdout.toString(StandardCharsets.UTF_8)).isEqualTo("回答\n");
    assertThat(stderr.toString(StandardCharsets.UTF_8)).isEqualTo("MODEL_TIMEOUT\n");

    var failing = new Utf8CliOutput(new FailingOutputStream(), new ByteArrayOutputStream());
    assertThatThrownBy(() -> failing.writeStdout("secret-output"))
        .isInstanceOf(CliOutputException.class)
        .hasMessage("CLI stdout 写入失败")
        .hasNoCause()
        .hasMessageNotContaining("secret-output")
        .hasMessageNotContaining("io-secret");
  }

  @Test
  void wiresConfiguredStreamingBudgetIntoProductionChatService() throws Exception {
    Path workspace = tempDir.resolve("streaming-budget");
    var properties =
        new AgentProperties(
            workspace,
            new AgentProperties.History(40, 100_000),
            new AgentProperties.Model(Duration.ofSeconds(5), 1, 10),
            new AgentProperties.ToolLoop(2),
            new AgentProperties.Tools(
                ToolRuntimeMode.DISABLED, 8, 16, Duration.ofSeconds(1), 2, 1024, 1024),
            new AgentProperties.Memory(
                MemoryRuntimeMode.DISABLED, 1024, 10_000, 10_000, null, null));
    var configuration = new ApplicationConfiguration();
    var schema = configuration.sqliteSchema(properties);
    var repository = configuration.jdbcSessionRepository(schema);
    ChatModelPort streamingModel =
        new ChatModelPort() {
          @Override
          public ChatModelResponse generate(io.namei.agent.kernel.model.ChatModelRequest request) {
            return new ChatModelResponse("ab");
          }

          @Override
          public ChatModelResponse generate(
              io.namei.agent.kernel.model.ChatModelRequest request,
              io.namei.agent.kernel.port.ChatModelStreamObserver observer,
              io.namei.agent.kernel.concurrent.CancellationSignal cancellation) {
            observer.onContentDelta("a");
            observer.onContentDelta("b");
            return new ChatModelResponse("ab");
          }
        };
    var useCase =
        configuration.chatUseCase(
            configuration.sessionRepository(repository),
            streamingModel,
            configuration.sessionExecutionGate(properties),
            configuration.turnLifecycleObserver(),
            configuration.approvalPort(),
            configuration.memoryContextService(
                configuration.memoryProfilePort(properties),
                io.namei.agent.kernel.port.MemoryRetrievalPort.disabled(),
                properties),
            McpRuntimes.disabled(),
            properties,
            "test-model",
            "",
            new ByteArrayResource("系统提示".getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(
            () ->
                useCase.chat(
                    new ChatCommand("session", "问题"), TurnCancellation.none(), ignored -> {}))
        .isInstanceOf(ModelStreamLimitExceededException.class);
    assertThat(repository.load("session").messages()).isEmpty();
  }

  @Test
  void cliModeDetectionRejectsNullArguments() {
    assertThatThrownBy(() -> CliMode.isRequested(null)).isInstanceOf(NullPointerException.class);
    assertThatCode(() -> CliMode.isRequested(new String[] {"--agent.config-check", "--cli"}))
        .doesNotThrowAnyException();
  }

  private static final class FailingOutputStream extends OutputStream {
    @Override
    public void write(int value) throws IOException {
      throw new IOException("io-secret");
    }
  }
}
