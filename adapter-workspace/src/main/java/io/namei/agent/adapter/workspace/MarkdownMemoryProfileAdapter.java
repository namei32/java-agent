package io.namei.agent.adapter.workspace;

import io.namei.agent.kernel.memory.MemoryProfile;
import io.namei.agent.kernel.port.MemoryProfilePort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class MarkdownMemoryProfileAdapter implements MemoryProfilePort {
  private static final String RECENT_TURNS_MARKER = "\n## Recent Turns";

  private final Path workspace;
  private final long maxFileBytes;

  public MarkdownMemoryProfileAdapter(Path workspace, long maxFileBytes) {
    this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
    if (maxFileBytes < 1 || maxFileBytes > Integer.MAX_VALUE - 8L) {
      throw new IllegalArgumentException("Memory Profile 文件上限必须在有效范围内");
    }
    this.maxFileBytes = maxFileBytes;
  }

  @Override
  public MemoryProfile load() {
    try {
      Path root = workspaceRoot();
      if (root == null) {
        return MemoryProfile.empty();
      }
      Path memory = memoryRoot(root);
      if (memory == null) {
        return MemoryProfile.empty();
      }
      String selfModel = readFixedFile(root, memory, "SELF.md");
      String longTerm = readFixedFile(root, memory, "MEMORY.md");
      String recent = trimRecentTurns(readFixedFile(root, memory, "RECENT_CONTEXT.md"));
      return new MemoryProfile(selfModel, longTerm, recent);
    } catch (MemoryProfileAccessException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new MemoryProfileAccessException();
    }
  }

  private Path workspaceRoot() throws IOException {
    if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    Path root = workspace.toRealPath();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new MemoryProfileAccessException();
    }
    return root;
  }

  private static Path memoryRoot(Path root) throws IOException {
    Path memory = root.resolve("memory");
    if (!Files.exists(memory, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    Path realMemory = memory.toRealPath();
    if (!realMemory.startsWith(root) || !Files.isDirectory(realMemory, LinkOption.NOFOLLOW_LINKS)) {
      throw new MemoryProfileAccessException();
    }
    return realMemory;
  }

  private String readFixedFile(Path root, Path memory, String name) throws IOException {
    Path candidate = memory.resolve(name);
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      return "";
    }
    Path realFile = candidate.toRealPath();
    if (!realFile.startsWith(root)
        || !Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)
        || Files.size(realFile) > maxFileBytes) {
      throw new MemoryProfileAccessException();
    }
    byte[] bytes = readBounded(realFile);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private byte[] readBounded(Path file) throws IOException {
    int limit = Math.toIntExact(maxFileBytes);
    try (InputStream input = Files.newInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8_192))) {
      byte[] buffer = new byte[Math.min(limit + 1, 8_192)];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > limit) {
          throw new MemoryProfileAccessException();
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private static String trimRecentTurns(String content) {
    int marker = content.indexOf(RECENT_TURNS_MARKER);
    return (marker < 0 ? content : content.substring(0, marker)).strip();
  }
}
