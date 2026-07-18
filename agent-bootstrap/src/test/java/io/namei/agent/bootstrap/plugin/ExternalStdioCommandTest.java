package io.namei.agent.bootstrap.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalStdioCommandTest {
  @Test
  void acceptsAnAbsoluteExecutableAndOpaqueArgumentTokens() {
    var command = new ExternalStdioCommand(List.of(javaExecutable().toString(), "-version"));

    assertThat(command.tokens()).containsExactly(javaExecutable().toString(), "-version");
  }

  @Test
  void rejectsShellsRelativeExecutableAndControlCharacters() {
    assertThatThrownBy(() -> new ExternalStdioCommand(List.of("/bin/sh", "-c", "echo unsafe")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExternalStdioCommand(List.of("python", "plugin.py")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ExternalStdioCommand(List.of(javaExecutable().toString(), "bad\narg")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Path javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize();
  }
}
