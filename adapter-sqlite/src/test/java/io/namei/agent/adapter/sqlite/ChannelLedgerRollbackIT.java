package io.namei.agent.adapter.sqlite;

import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.quickCheck;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.rawConnection;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.schemaVersion;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.tables;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("failure")
class ChannelLedgerRollbackIT {
  private static final String DATABASE_NAME = "channel-ledger.db";

  @TempDir Path tempDir;

  @Test
  void restoresAValidatedV0BackupOfflineWithoutChangingTheOnlyBackup() throws Exception {
    Path database = tempDir.resolve("workspace/channels/" + DATABASE_NAME);
    ChannelLedgerSchemaTestSupport.createV0(database);
    var initializer = new ChannelLedgerSchemaInitializer(database, 5_000);

    initializer.initialize();

    Path backup = onlyMigrationBackup(database);
    String originalBackupHash = sha256(backup);
    assertV1(database);
    assertV0(backup);

    Path firstQuarantine = restoreOffline(backup, database, "failed-v1-first");

    assertThat(firstQuarantine.resolve(DATABASE_NAME)).isRegularFile();
    assertV1(firstQuarantine.resolve(DATABASE_NAME));
    assertV0(database);
    assertThat(sha256(backup)).isEqualTo(originalBackupHash);
    assertThat(database.resolveSibling(DATABASE_NAME + "-wal")).doesNotExist();
    assertThat(database.resolveSibling(DATABASE_NAME + "-shm")).doesNotExist();

    Path secondQuarantine = restoreOffline(backup, database, "restored-v0-second");

    assertThat(secondQuarantine.resolve(DATABASE_NAME)).isRegularFile();
    assertV0(secondQuarantine.resolve(DATABASE_NAME));
    assertV0(database);
    assertThat(sha256(backup)).isEqualTo(originalBackupHash);

    new ChannelLedgerSchemaInitializer(database, 5_000).initialize();

    assertV1(database);
    assertThat(sha256(backup)).isEqualTo(originalBackupHash);
    assertThat(migrationBackups(database)).hasSize(2);
  }

  private static Path restoreOffline(Path backup, Path database, String quarantineName)
      throws Exception {
    assertV0(backup);
    Path staging = database.resolveSibling("." + DATABASE_NAME + ".restore");
    Files.copy(backup, staging, StandardCopyOption.COPY_ATTRIBUTES);
    assertV0(staging);

    Path quarantine = database.resolveSibling(quarantineName);
    Files.createDirectory(quarantine);
    moveIfPresent(database, quarantine.resolve(DATABASE_NAME));
    moveIfPresent(
        database.resolveSibling(DATABASE_NAME + "-wal"),
        quarantine.resolve(DATABASE_NAME + "-wal"));
    moveIfPresent(
        database.resolveSibling(DATABASE_NAME + "-shm"),
        quarantine.resolve(DATABASE_NAME + "-shm"));
    Files.move(staging, database, StandardCopyOption.ATOMIC_MOVE);
    return quarantine;
  }

  private static void moveIfPresent(Path source, Path destination) throws IOException {
    if (Files.exists(source)) {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    }
  }

  private static Path onlyMigrationBackup(Path database) throws IOException {
    List<Path> backups = migrationBackups(database);
    assertThat(backups).hasSize(1);
    return backups.getFirst();
  }

  private static List<Path> migrationBackups(Path database) throws IOException {
    try (var files = Files.list(database.getParent())) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().startsWith(DATABASE_NAME + ".v0-to-v1-"))
          .filter(path -> path.getFileName().toString().endsWith(".bak"))
          .sorted()
          .toList();
    }
  }

  private static void assertV0(Path database) throws Exception {
    try (var connection = rawConnection(database)) {
      assertThat(quickCheck(connection)).isEqualToIgnoringCase("ok");
      assertThat(schemaVersion(connection)).isZero();
      assertThat(tables(connection)).containsExactly("channel_schema");
      assertThat(ChannelLedgerSchemaTestSupport.viewsAndTriggers(connection)).isEmpty();
    }
  }

  private static void assertV1(Path database) throws Exception {
    try (var connection = rawConnection(database)) {
      assertThat(quickCheck(connection)).isEqualToIgnoringCase("ok");
      assertThat(schemaVersion(connection)).isOne();
      assertThat(tables(connection))
          .containsExactlyInAnyOrder(
              "channel_schema",
              "channel_cursors",
              "channel_inbox_events",
              "channel_turn_claims",
              "channel_deliveries",
              "channel_delivery_parts",
              "channel_delivery_attempts");
      assertThat(ChannelLedgerSchemaTestSupport.viewsAndTriggers(connection)).isEmpty();
    }
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }
}
