package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.sqlite.ChannelLedgerSchemaInitializer;
import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlPlaneCancellationIT {
  @TempDir Path temporaryDirectory;

  @Test
  void targetCancellationUsesExistingSourceAndNeverWritesReliableLedger() throws Exception {
    Path ledger = temporaryDirectory.resolve("channel-ledger.db");
    new ChannelLedgerSchemaInitializer(ledger, 5_000).initialize();
    byte[] before = Files.readAllBytes(ledger);
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var source = new TurnCancellationSource();
    var registration =
        runtime.register(
            "telegram",
            ControlCancellationHandle.from(source),
            Instant.parse("2026-07-18T00:00:00Z"));

    var outcome = runtime.registry().cancel(registration.turnRef().orElseThrow());

    assertThat(outcome.result().name()).isEqualTo("CANCELLATION_REQUESTED");
    assertThat(source.token().reason()).isEqualTo(TurnCancellationCode.REQUESTED);
    assertThat(Files.readAllBytes(ledger)).isEqualTo(before);
  }
}
