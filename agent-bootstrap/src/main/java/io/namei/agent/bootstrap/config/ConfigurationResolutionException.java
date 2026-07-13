package io.namei.agent.bootstrap.config;

import java.util.List;
import java.util.stream.Collectors;

final class ConfigurationResolutionException extends IllegalStateException {
  private final List<ConfigurationDiagnostic> diagnostics;

  ConfigurationResolutionException(List<ConfigurationDiagnostic> diagnostics) {
    super(publicMessage(diagnostics));
    this.diagnostics = List.copyOf(diagnostics);
  }

  List<ConfigurationDiagnostic> diagnostics() {
    return diagnostics;
  }

  private static String publicMessage(List<ConfigurationDiagnostic> diagnostics) {
    String details =
        diagnostics.stream()
            .map(diagnostic -> diagnostic.code().name() + "(" + diagnostic.field() + ")")
            .collect(Collectors.joining(", "));
    return "配置无效: " + details;
  }
}
