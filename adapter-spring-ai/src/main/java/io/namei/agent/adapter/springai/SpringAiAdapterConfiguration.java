package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.port.ChatModelPort;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpringAiAdapterConfiguration {
  @Bean
  ChatModelPort chatModelPort(
      ChatModel chatModel,
      @Value("${agent.tools.max-argument-bytes:16384}") int maxArgumentBytes,
      @Value("${agent.model.stream-idle-timeout:30s}") Duration streamIdleTimeout) {
    return new SpringAiChatModelAdapter(chatModel, maxArgumentBytes, streamIdleTimeout);
  }
}
