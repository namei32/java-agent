package io.namei.agent.adapter.workspace.cutover;

import io.namei.agent.kernel.cutover.CutoverArtifactCategory;
import io.namei.agent.kernel.cutover.CutoverBackupEntry;
import io.namei.agent.kernel.cutover.CutoverBackupManifest;
import io.namei.agent.kernel.cutover.CutoverDifferenceReport;
import io.namei.agent.kernel.port.CutoverSandboxPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Filesystem adapter restricted to a newly created, explicitly selected rehearsal sandbox. */
public final class SandboxCutoverAdapter implements CutoverSandboxPort {
  private static final long MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;
  private static final String SANDBOX_MARKER = ".namei-cutover-sandbox-v1";
  private static final String SANDBOX_MARKER_CONTENT = "namei-cutover-sandbox-v1\n";

  private final Path root;

  private SandboxCutoverAdapter(Path root) {
    this.root = root;
  }

  public static SandboxCutoverAdapter createNew(
      Path sandboxRoot, Path repositoryRoot, Path configuredWorkspace, Path home) {
    Path root = normalized(sandboxRoot);
    rejectForbidden(root, repositoryRoot, configuredWorkspace, home);
    try {
      if (Files.exists(root) || Files.isSymbolicLink(root) || root.getParent() == null) {
        throw SandboxCutoverException.unsafe(null);
      }
      Files.createDirectory(root);
      Files.createDirectories(root.resolve("input"));
      Files.writeString(
          root.resolve(SANDBOX_MARKER), SANDBOX_MARKER_CONTENT, StandardCharsets.UTF_8);
      return new SandboxCutoverAdapter(root);
    } catch (SandboxCutoverException exception) {
      throw exception;
    } catch (IOException exception) {
      throw SandboxCutoverException.unsafe(exception);
    }
  }

  public static SandboxCutoverAdapter openExisting(
      Path sandboxRoot, Path repositoryRoot, Path configuredWorkspace, Path home) {
    Path root = normalized(sandboxRoot);
    rejectForbidden(root, repositoryRoot, configuredWorkspace, home);
    try {
      Path marker = root.resolve(SANDBOX_MARKER);
      if (!Files.isDirectory(root)
          || Files.isSymbolicLink(root)
          || !Files.isDirectory(root.resolve("input"))
          || Files.isSymbolicLink(root.resolve("input"))
          || !Files.isRegularFile(marker)
          || Files.isSymbolicLink(marker)
          || !SANDBOX_MARKER_CONTENT.equals(Files.readString(marker, StandardCharsets.UTF_8))) {
        throw SandboxCutoverException.unsafe(null);
      }
      return new SandboxCutoverAdapter(root);
    } catch (SandboxCutoverException exception) {
      throw exception;
    } catch (IOException exception) {
      throw SandboxCutoverException.unsafe(exception);
    }
  }

  public Path root() {
    return root;
  }

  @Override
  public CutoverBackupManifest backup() {
    List<SourceEntry> source = inventory();
    String id = "backup-" + UUID.randomUUID();
    Path staging = root.resolve(".staging-" + id);
    Path destination = root.resolve("backups").resolve(id);
    try {
      if (Files.exists(staging) || Files.exists(destination)) {
        throw SandboxCutoverException.backup(null);
      }
      Files.createDirectories(staging.resolve("files"));
      var entries = new ArrayList<CutoverBackupEntry>();
      for (SourceEntry entry : source) {
        Path output = resolveInside(staging.resolve("files"), entry.relativePath());
        Files.createDirectories(output.getParent());
        Files.copy(entry.path(), output, StandardCopyOption.COPY_ATTRIBUTES);
        entries.add(
            new CutoverBackupEntry(
                entry.category(), entry.relativePath(), entry.sha256(), entry.bytes()));
      }
      CutoverBackupManifest manifest = new CutoverBackupManifest(id, entries);
      Files.writeString(
          staging.resolve("manifest-v1.txt"), encode(manifest), StandardCharsets.UTF_8);
      Files.createDirectories(destination.getParent());
      try {
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        throw SandboxCutoverException.backup(unsupported);
      }
      return manifest;
    } catch (SandboxCutoverException exception) {
      deleteQuietly(staging);
      throw exception;
    } catch (IOException exception) {
      deleteQuietly(staging);
      throw SandboxCutoverException.backup(exception);
    }
  }

  @Override
  public boolean verify(CutoverBackupManifest expected) {
    try {
      Path backup = resolveInside(root.resolve("backups"), expected.backupId());
      CutoverBackupManifest stored =
          decode(Files.readString(backup.resolve("manifest-v1.txt"), StandardCharsets.UTF_8));
      if (!stored.equals(expected)) {
        return false;
      }
      for (CutoverBackupEntry entry : stored.entries()) {
        Path file = resolveInside(backup.resolve("files"), entry.relativePath());
        if (!Files.isRegularFile(file)
            || Files.isSymbolicLink(file)
            || Files.size(file) != entry.bytes()
            || !sha256(file).equals(entry.sha256())) {
          return false;
        }
      }
      return true;
    } catch (IOException | IllegalArgumentException exception) {
      return false;
    }
  }

  public CutoverBackupManifest latestManifest() {
    Path backups = root.resolve("backups");
    try (Stream<Path> entries = Files.list(backups)) {
      Path latest =
          entries
              .filter(Files::isDirectory)
              .filter(path -> path.getFileName().toString().startsWith("backup-"))
              .max(Comparator.comparing(path -> path.getFileName().toString()))
              .orElseThrow(() -> SandboxCutoverException.backup(null));
      return decode(Files.readString(latest.resolve("manifest-v1.txt"), StandardCharsets.UTF_8));
    } catch (SandboxCutoverException exception) {
      throw exception;
    } catch (IOException exception) {
      throw SandboxCutoverException.backup(exception);
    }
  }

  @Override
  public CutoverDifferenceReport compare(CutoverBackupManifest manifest, int threshold) {
    if (threshold < 0) {
      throw new IllegalArgumentException("差异阈值不能为负数");
    }
    Map<String, SourceEntry> current = new LinkedHashMap<>();
    for (SourceEntry entry : inventory()) {
      current.put(entry.relativePath(), entry);
    }
    int changed = 0;
    for (CutoverBackupEntry entry : manifest.entries()) {
      SourceEntry source = current.remove(entry.relativePath());
      if (source == null
          || source.bytes() != entry.bytes()
          || !source.sha256().equals(entry.sha256())) {
        changed++;
      }
    }
    changed += current.size();
    return new CutoverDifferenceReport(
        manifest.entries().size() + current.size(), changed, threshold);
  }

  private List<SourceEntry> inventory() {
    Path input = root.resolve("input");
    try (Stream<Path> paths = Files.walk(input)) {
      var entries = new ArrayList<SourceEntry>();
      for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
        if (path.equals(input)) {
          continue;
        }
        if (Files.isSymbolicLink(path)) {
          throw SandboxCutoverException.unsafe(null);
        }
        if (Files.isDirectory(path)) {
          continue;
        }
        if (!Files.isRegularFile(path)) {
          throw SandboxCutoverException.unsafe(null);
        }
        String relative =
            input.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        CutoverArtifactCategory category = category(relative);
        long bytes = Files.size(path);
        if (bytes > MAX_ARTIFACT_BYTES) {
          throw SandboxCutoverException.backup(null);
        }
        entries.add(new SourceEntry(category, relative, path, bytes, sha256(path)));
      }
      if (entries.isEmpty()) {
        throw SandboxCutoverException.unsafe(null);
      }
      return List.copyOf(entries);
    } catch (SandboxCutoverException exception) {
      throw exception;
    } catch (IOException exception) {
      throw SandboxCutoverException.unsafe(exception);
    }
  }

  private static CutoverArtifactCategory category(String relative) {
    if (relative.equals("config/config.toml")) {
      return CutoverArtifactCategory.CONFIG;
    }
    if (relative.matches("memory/[A-Za-z0-9._-]{1,120}\\.md")) {
      return CutoverArtifactCategory.MARKDOWN_MEMORY;
    }
    if (relative.matches("sqlite/[A-Za-z0-9._-]{1,120}\\.db(-wal|-shm)?")) {
      return CutoverArtifactCategory.SQLITE;
    }
    throw SandboxCutoverException.unsafe(null);
  }

  private static void rejectForbidden(Path root, Path... forbidden) {
    for (Path candidate : forbidden) {
      Path protectedRoot = normalized(candidate);
      if (root.startsWith(protectedRoot) || protectedRoot.startsWith(root)) {
        throw SandboxCutoverException.unsafe(null);
      }
    }
  }

  private static Path normalized(Path path) {
    if (path == null) {
      throw SandboxCutoverException.unsafe(null);
    }
    return path.toAbsolutePath().normalize();
  }

  private static Path resolveInside(Path root, String relative) {
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root.normalize())) {
      throw SandboxCutoverException.unsafe(null);
    }
    return resolved;
  }

  private static String encode(CutoverBackupManifest manifest) {
    var output = new StringBuilder("format=1\nbackup=").append(manifest.backupId()).append('\n');
    for (CutoverBackupEntry entry : manifest.entries()) {
      output
          .append("entry\t")
          .append(entry.category())
          .append('\t')
          .append(entry.relativePath())
          .append('\t')
          .append(entry.sha256())
          .append('\t')
          .append(entry.bytes())
          .append('\n');
    }
    return output.toString();
  }

  private static CutoverBackupManifest decode(String manifest) {
    String[] lines = manifest.split("\\n", -1);
    if (lines.length < 3 || !"format=1".equals(lines[0]) || !lines[1].startsWith("backup=")) {
      throw SandboxCutoverException.backup(null);
    }
    var entries = new ArrayList<CutoverBackupEntry>();
    for (int index = 2; index < lines.length; index++) {
      if (lines[index].isEmpty()) {
        continue;
      }
      String[] fields = lines[index].split("\\t", -1);
      if (fields.length != 5 || !"entry".equals(fields[0])) {
        throw SandboxCutoverException.backup(null);
      }
      entries.add(
          new CutoverBackupEntry(
              CutoverArtifactCategory.valueOf(fields[1]),
              fields[2],
              fields[3],
              Long.parseLong(fields[4])));
    }
    return new CutoverBackupManifest(lines[1].substring("backup=".length()), entries);
  }

  private static String sha256(Path path) throws IOException {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(path)) {
      paths.sorted(Comparator.reverseOrder()).forEach(SandboxCutoverAdapter::deleteOne);
    } catch (IOException ignored) {
      // A failed staging cleanup does not conceal the primary error and is kept inside sandbox.
    }
  }

  private static void deleteOne(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup only.
    }
  }

  private record SourceEntry(
      CutoverArtifactCategory category,
      String relativePath,
      Path path,
      long bytes,
      String sha256) {}
}
