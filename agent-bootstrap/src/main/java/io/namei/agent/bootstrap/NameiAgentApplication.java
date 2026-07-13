package io.namei.agent.bootstrap;

import io.namei.agent.bootstrap.config.ConfigurationCheckCommand;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NameiAgentApplication {
  public static void main(String[] args) {
    if (ConfigurationCheckCommand.isRequested(args)) {
      int exitCode =
          ConfigurationCheckCommand.run(
              args,
              System.getenv(),
              Path.of(System.getProperty("user.dir", ".")),
              System.out);
      if (exitCode != 0) {
        System.exit(exitCode);
      }
      return;
    }
    SpringApplication.run(NameiAgentApplication.class, args);
  }
}
