package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.port.ChatModelPort;
import java.time.Duration;
import java.util.Locale;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpringAiAdapterConfiguration {
  @Bean
  OpenAiStreamCancellationRegistry openAiStreamCancellationRegistry() {
    return new OpenAiStreamCancellationRegistry();
  }

  @Bean
  ChatModelPort chatModelPort(
      ChatModel chatModel,
      OpenAiStreamCancellationRegistry streamCancellationRegistry,
      @Value("${agent.tools.max-argument-bytes:16384}") int maxArgumentBytes,
      @Value("${agent.model.stream-idle-timeout:30s}") String streamIdleTimeout) {
    return new SpringAiChatModelAdapter(
        chatModel, maxArgumentBytes, parseDuration(streamIdleTimeout), streamCancellationRegistry);
  }

  private static Duration parseDuration(String configured) {
    if (configured == null || configured.isBlank()) {
      throw new IllegalArgumentException("agent.model.stream-idle-timeout 不能为空");
    }
    String value = configured.strip().toLowerCase(Locale.ROOT);
    try {
      if (value.startsWith("p")) {
        return Duration.parse(value.toUpperCase(Locale.ROOT));
      }
      String unit = value.endsWith("ms") ? "ms" : value.substring(value.length() - 1);
      String amount = value.substring(0, value.length() - unit.length());
      long parsed = Long.parseLong(amount);
      return switch (unit) {
        case "ms" -> Duration.ofMillis(parsed);
        case "s" -> Duration.ofSeconds(parsed);
        case "m" -> Duration.ofMinutes(parsed);
        case "h" -> Duration.ofHours(parsed);
        case "d" -> Duration.ofDays(parsed);
        default -> throw new IllegalArgumentException("不支持的 Duration 单位");
      };
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("agent.model.stream-idle-timeout 格式无效", exception);
    }
  }
}
