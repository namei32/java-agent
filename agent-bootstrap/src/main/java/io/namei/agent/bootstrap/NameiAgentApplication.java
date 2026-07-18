package io.namei.agent.bootstrap;

import io.namei.agent.bootstrap.cli.CliMode;
import io.namei.agent.bootstrap.cli.LocalCliRunner;
import io.namei.agent.bootstrap.config.ConfigurationCheckCommand;
import io.namei.agent.bootstrap.cutover.CutoverCommand;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NameiAgentApplication {
  public static void main(String[] args) {
    if (CutoverCommand.isRequested(args)) {
      int exitCode =
          CutoverCommand.run(
              args,
              Path.of(System.getProperty("user.dir", ".")),
              Path.of(System.getenv().getOrDefault("AKASHIC_WORKSPACE", "./workspace")),
              Path.of(System.getProperty("user.home", ".")),
              System.out);
      if (exitCode != 0) {
        System.exit(exitCode);
      }
      return;
    }
    if (ConfigurationCheckCommand.isRequested(args)) {
      int exitCode =
          ConfigurationCheckCommand.run(
              args, System.getenv(), Path.of(System.getProperty("user.dir", ".")), System.out);
      if (exitCode != 0) {
        System.exit(exitCode);
      }
      return;
    }
    boolean cli = CliMode.isRequested(args);
    var context = application(args).run(args);
    if (cli) {
      try (context) {
        context.getBean(LocalCliRunner.class).run();
      }
    }
  }

  public static SpringApplication application(String[] args) {
    Objects.requireNonNull(args, "args");
    var application = new SpringApplication(NameiAgentApplication.class);
    if (CliMode.isRequested(args)) {
      application.setWebApplicationType(WebApplicationType.NONE);
      application.setBannerMode(Banner.Mode.OFF);
      application.setLogStartupInfo(false);
      application.setDefaultProperties(Map.of("logging.console.enabled", "false"));
    }
    return application;
  }
}
