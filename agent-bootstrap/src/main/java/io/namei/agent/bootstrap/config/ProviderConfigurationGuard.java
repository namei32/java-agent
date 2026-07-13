package io.namei.agent.bootstrap.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.core.env.Environment;

public final class ProviderConfigurationGuard {
  private static final String BASE_URL_PROPERTY = "spring.ai.openai.base-url";

  private final Environment environment;

  public ProviderConfigurationGuard(Environment environment) {
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  public void validate() {
    var missing = new ArrayList<String>();
    require(BASE_URL_PROPERTY, "base-url", missing);
    require("spring.ai.openai.api-key", "api-key", missing);
    require("spring.ai.openai.chat.model", "model", missing);
    if (!missing.isEmpty()) {
      throw new IllegalStateException("缺少必需模型配置: " + String.join(", ", missing));
    }
    validateBaseUrl(environment.getRequiredProperty(BASE_URL_PROPERTY));
  }

  private void require(String property, String label, List<String> missing) {
    String value = environment.getProperty(property, "");
    if (value.isBlank()) {
      missing.add(label);
    }
  }

  private static void validateBaseUrl(String value) {
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme();
      if (uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
        throw new IllegalArgumentException("unsupported URI");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("模型 Base URL 无效");
    }
  }
}
