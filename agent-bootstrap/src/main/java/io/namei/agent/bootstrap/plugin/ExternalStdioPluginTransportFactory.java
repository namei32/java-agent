package io.namei.agent.bootstrap.plugin;

import java.io.IOException;

@FunctionalInterface
public interface ExternalStdioPluginTransportFactory {
  ExternalStdioPluginTransport start(ExternalStdioCommand command, ExternalStdioBridgeLimits limits)
      throws IOException;
}
