package io.namei.agent.adapter.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class DefaultMcpConfigFileReader implements McpConfigFileReader {
  @Override
  public byte[] read(Path path, int maxBytes) throws IOException {
    if (path == null || !path.isAbsolute()) {
      throw new McpConfigurationException();
    }
    Path normalized = path.normalize();
    if (Files.isSymbolicLink(normalized)
        || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
        || Files.size(normalized) > maxBytes) {
      throw new McpConfigurationException();
    }
    Path real = normalized.toRealPath();
    if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
      throw new McpConfigurationException();
    }
    try (InputStream input = Files.newInputStream(real);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8_192))) {
      byte[] buffer = new byte[Math.min(maxBytes + 1, 8_192)];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > maxBytes) {
          throw new McpConfigurationException();
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }
}
