package io.namei.agent.adapter.mcp;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface McpConfigFileReader {
  byte[] read(Path path, int maxBytes) throws IOException;
}
