package io.namei.agent.adapter.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** 解析单个 Workspace 相对对象，不接受链接或发生变化的 Root。 */
final class WorkspacePathResolver {
  private final Path configuredRoot;
  private final Path realRoot;

  private WorkspacePathResolver(Path configuredRoot, Path realRoot) {
    this.configuredRoot = configuredRoot;
    this.realRoot = realRoot;
  }

  static WorkspacePathResolver open(Path configuredRoot) {
    if (configuredRoot == null || !configuredRoot.isAbsolute()) {
      throw unavailable();
    }
    Path absolute = configuredRoot.normalize();
    try {
      if (Files.isSymbolicLink(absolute)
          || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
        throw unavailable();
      }
      Path real = absolute.toRealPath();
      if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
        throw unavailable();
      }
      return new WorkspacePathResolver(absolute, real);
    } catch (IOException | SecurityException invalid) {
      throw unavailable();
    }
  }

  ResolvedPath resolve(WorkspaceToolPath relative) {
    Objects.requireNonNull(relative, "relative");
    verifyRoot();
    Path current = realRoot;
    BasicFileAttributes attributes = null;
    String[] segments = relative.value().split("/", -1);
    for (int index = 0; index < segments.length; index++) {
      Path candidate = current.resolve(segments[index]);
      try {
        attributes =
            Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
          throw rejected();
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(realRoot) || !real.equals(candidate)) {
          throw rejected();
        }
        if (index < segments.length - 1 && !attributes.isDirectory()) {
          throw rejected();
        }
        current = real;
      } catch (NoSuchFileException missing) {
        throw WorkspaceToolError.WORKSPACE_NOT_FOUND.violation();
      } catch (WorkspaceToolContractException violation) {
        throw violation;
      } catch (IOException | SecurityException invalid) {
        throw rejected();
      }
    }
    return new ResolvedPath(this, current, attributes);
  }

  void verifyRoot() {
    try {
      if (Files.isSymbolicLink(configuredRoot)
          || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)
          || !configuredRoot.toRealPath().equals(realRoot)) {
        throw unavailable();
      }
    } catch (IOException | SecurityException invalid) {
      throw unavailable();
    }
  }

  private void verify(Path path, BasicFileAttributes original) {
    verifyRoot();
    try {
      BasicFileAttributes current =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (current.isSymbolicLink()
          || !path.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(path)
          || !sameFile(original, current)) {
        throw rejected();
      }
    } catch (NoSuchFileException missing) {
      throw WorkspaceToolError.WORKSPACE_NOT_FOUND.violation();
    } catch (WorkspaceToolContractException violation) {
      throw violation;
    } catch (IOException | SecurityException invalid) {
      throw rejected();
    }
  }

  private static boolean sameFile(BasicFileAttributes first, BasicFileAttributes second) {
    Object firstKey = first.fileKey();
    Object secondKey = second.fileKey();
    return firstKey != null
        && firstKey.equals(secondKey)
        && first.isDirectory() == second.isDirectory()
        && first.isRegularFile() == second.isRegularFile();
  }

  private static WorkspaceToolContractException rejected() {
    return WorkspaceToolError.WORKSPACE_PATH_REJECTED.violation();
  }

  private static WorkspaceToolContractException unavailable() {
    return WorkspaceToolError.WORKSPACE_TOOL_UNAVAILABLE.violation();
  }

  record ResolvedPath(
      WorkspacePathResolver resolver, Path path, BasicFileAttributes initialAttributes) {
    ResolvedPath {
      Objects.requireNonNull(resolver, "resolver");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(initialAttributes, "initialAttributes");
    }

    void verifyUnchanged() {
      resolver.verify(path, initialAttributes);
    }
  }
}
