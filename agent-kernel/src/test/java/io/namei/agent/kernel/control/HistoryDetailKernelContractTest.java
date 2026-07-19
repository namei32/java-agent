package io.namei.agent.kernel.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.port.ControlHistorySnapshotPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryDetailKernelContractTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

  @Test
  void acceptsOnlyOpaqueScopeBindingsAndRedactsThem() {
    HistoryScopeCapability scope = HistoryScopeCapability.fromTrustedDigest("a".repeat(64));

    assertThat(scope.toString()).isEqualTo("HistoryScopeCapability[redacted]");
    assertThat(HistoryScopeCapability.fromTrustedDigest("a".repeat(64))).isEqualTo(scope);
    assertThatThrownBy(() -> HistoryScopeCapability.fromTrustedDigest("session-raw-secret"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesAndRedactsOpaqueDetailReferencesAndCursors() {
    byte[] bytes = new byte[16];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) index;
    }

    HistoryDetailRef reference = HistoryDetailRef.fromBytes(bytes);
    HistoryPageCursor cursor = HistoryPageCursor.fromBytes(bytes);

    assertThat(reference.value()).isEqualTo("AAECAwQFBgcICQoLDA0ODw");
    assertThat(HistoryDetailRef.parse(reference.value())).isEqualTo(reference);
    assertThat(HistoryPageCursor.parse(cursor.value())).isEqualTo(cursor);
    assertThat(reference.toString()).doesNotContain(reference.value());
    assertThat(cursor.toString()).doesNotContain(cursor.value());
    assertThatThrownBy(() -> HistoryDetailRef.parse("session-raw-secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HistoryPageCursor.fromBytes(new byte[15]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundsZeroContentMetadataPagesAndReadRequests() {
    HistoryDetailRef reference = HistoryDetailRef.fromBytes(new byte[16]);
    HistoryDetailReadRequest request = new HistoryDetailReadRequest(reference, 10, NOW);
    HistoryDetailItem item = new HistoryDetailItem(HistoryVisibleRole.USER, NOW.minusSeconds(1));
    HistoryDetailPage page = new HistoryDetailPage(List.of(item), true);

    assertThat(request.toString()).doesNotContain(reference.value());
    assertThat(page.items()).containsExactly(item);
    assertThat(page.toString()).doesNotContain("private message body");
    assertThatThrownBy(() -> new HistoryDetailReadRequest(reference, 21, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HistoryDetailPage(java.util.Collections.nCopies(21, item), false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exposesOnlyTheNarrowReadPortAndFailsClosedWhenDisabled() {
    HistoryScopeCapability scope = HistoryScopeCapability.fromTrustedDigest("b".repeat(64));
    HistoryDetailReadRequest request =
        new HistoryDetailReadRequest(HistoryDetailRef.fromBytes(new byte[16]), 1, NOW);
    HistoryDetailPage page = new HistoryDetailPage(List.of(), false);
    ControlHistorySnapshotPort port = (boundScope, boundRequest) -> page;

    assertThat(port.read(scope, request)).isEqualTo(page);
    assertThatThrownBy(() -> ControlHistorySnapshotPort.disabled().read(scope, request))
        .isInstanceOf(HistorySnapshotUnavailableException.class);
  }
}
