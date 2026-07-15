package io.namei.agent.bootstrap.config;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public final class JavaNativeMemoryEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  static final String PROPERTY_SOURCE_NAME = "nameiJavaNativeMemory";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String mode = environment.getProperty("agent.memory.mode");
    if (mode == null || !"JAVA_NATIVE".equalsIgnoreCase(mode.strip())) {
      return;
    }
    environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of("spring.ai.model.embedding", "openai")));
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 2;
  }
}
