package io.namei.agent.bootstrap;

import io.namei.agent.adapter.springai.SpringAiAdapterConfiguration;
import io.namei.agent.bootstrap.config.ApplicationConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ApplicationConfiguration.class, SpringAiAdapterConfiguration.class})
public class NameiAgentApplication {
  public static void main(String[] args) {
    SpringApplication.run(NameiAgentApplication.class, args);
  }
}
