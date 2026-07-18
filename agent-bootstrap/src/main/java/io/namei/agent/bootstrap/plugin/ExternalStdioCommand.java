package io.namei.agent.bootstrap.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 不经 Shell 解析的、静态配置的外部 stdio 启动 token。 */
public record ExternalStdioCommand(List<String> tokens) {
  private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "fish", "dash", "cmd");

  public ExternalStdioCommand {
    tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
    if (tokens.isEmpty()
        || tokens.size() > 16
        || tokens.stream().anyMatch(ExternalStdioCommand::invalid)) {
      throw new IllegalArgumentException("External stdio command token 非法");
    }
    Path executable;
    try {
      Path configured = Path.of(tokens.getFirst());
      if (!configured.isAbsolute()) {
        throw new IllegalArgumentException("External stdio executable 必须为绝对路径");
      }
      executable = configured.normalize();
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("External stdio executable 非法", invalid);
    }
    String executableName = executable.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (SHELLS.contains(executableName)
        || !Files.isRegularFile(executable)
        || !Files.isExecutable(executable)) {
      throw new IllegalArgumentException("External stdio executable 必须是非 Shell 的绝对可执行文件");
    }
    tokens = List.copyOf(tokens);
  }

  private static boolean invalid(String token) {
    return token == null
        || token.isBlank()
        || token.length() > 512
        || token.codePoints().anyMatch(Character::isISOControl);
  }
}
