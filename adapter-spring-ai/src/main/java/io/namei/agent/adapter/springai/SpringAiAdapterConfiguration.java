package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.port.ChatModelPort;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods = false)
public class SpringAiAdapterConfiguration {
  @Bean
  ChatModelPort chatModelPort(
      ChatModel chatModel,
      @Value("${agent.tools.max-argument-bytes:16384}") int maxArgumentBytes) {
    return new SpringAiChatModelAdapter(chatModel, maxArgumentBytes);
  }
}
