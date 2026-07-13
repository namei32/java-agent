package io.namei.agent.bootstrap.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

public final class AgentConfigurationEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  static final String PROPERTY_SOURCE_NAME = "nameiAgentConfigurationCompatibility";

  private final Path workingDirectory;
  private final Map<String, String> processEnvironment;

  public AgentConfigurationEnvironmentPostProcessor() {
    this(Path.of(System.getProperty("user.dir", ".")), System.getenv());
  }

  AgentConfigurationEnvironmentPostProcessor(
      Path workingDirectory, Map<String, String> processEnvironment) {
    this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
    this.processEnvironment = Map.copyOf(processEnvironment);
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String commandLineConfigFile = commandLineConfigFile(environment);
    var resolution =
        new ConfigurationResolver()
            .resolve(
                new ConfigurationInputs(
                    workingDirectory, commandLineConfigFile, processEnvironment));
    if (resolution.mode() == ConfigurationMode.ENVIRONMENT) {
      return;
    }

    ActiveConfigurationSnapshot active = resolution.requireActive();
    var properties = new LinkedHashMap<String, Object>();
    properties.put("spring.ai.openai.base-url", active.baseUrl().toString());
    properties.put("spring.ai.openai.api-key", active.apiKey().reveal());
    properties.put("spring.ai.openai.chat.model", active.model());
    properties.put("agent.history.max-messages", active.historyMaxMessages());
    properties.put("agent.compatibility.mode", resolution.mode().name());
    resolution
        .configFile()
        .ifPresent(path -> properties.put("agent.compatibility.config-file", path.toString()));
    active
        .systemPrompt()
        .ifPresent(
            prompt ->
                properties.put(
                    "agent.compatibility.system-prompt-base64",
                    Base64.getEncoder()
                        .encodeToString(prompt.getBytes(StandardCharsets.UTF_8))));
    environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  private static String commandLineConfigFile(ConfigurableEnvironment environment) {
    PropertySource<?> commandLine =
        environment
            .getPropertySources()
            .get(CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME);
    if (commandLine == null) {
      return null;
    }
    Object configured = commandLine.getProperty("agent.config-file");
    return configured == null ? null : configured.toString();
  }
}
