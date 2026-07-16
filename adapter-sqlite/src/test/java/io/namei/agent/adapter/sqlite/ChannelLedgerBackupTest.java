package io.namei.agent.adapter.sqlite;

import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.createV0;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.quickCheck;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.rawConnection;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.schemaVersion;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelLedgerBackupTest {
  @TempDir Path tempDir;

  @Test
  void onlineBackupIsAStandaloneHealthyV0Database() throws Exception {
    Path database = tempDir.resolve("channels/channel-ledger.db");
    createV0(database);

    new ChannelLedgerSchemaInitializer(database, 5_000).initialize();

    Path backup;
    try (var files = Files.list(database.getParent())) {
      backup =
          files
              .filter(
                  path -> path.getFileName().toString().startsWith("channel-ledger.db.v0-to-v1-"))
              .filter(path -> path.getFileName().toString().endsWith(".bak"))
              .findFirst()
              .orElseThrow();
    }
    assertThat(Files.size(backup)).isPositive();
    try (var connection = rawConnection(backup)) {
      assertThat(quickCheck(connection)).isEqualToIgnoringCase("ok");
      assertThat(schemaVersion(connection)).isZero();
    }
  }
}
