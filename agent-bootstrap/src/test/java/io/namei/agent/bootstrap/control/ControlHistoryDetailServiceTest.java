package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.control.ControlPlaneContract;
import io.namei.agent.kernel.control.HistoryDetailItem;
import io.namei.agent.kernel.control.HistoryDetailPage;
import io.namei.agent.kernel.control.HistoryScopeCapability;
import io.namei.agent.kernel.control.HistorySnapshotUnavailableException;
import io.namei.agent.kernel.control.HistoryVisibleRole;
import io.namei.agent.kernel.port.ControlHistorySnapshotPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ControlHistoryDetailServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
  private static final String ACTOR = "A".repeat(22);
  private static final String OTHER_ACTOR = "B".repeat(22);
  private static final HistoryScopeCapability SCOPE =
      HistoryScopeCapability.fromTrustedDigest("a".repeat(64));

  @Test
  void issuesAndConsumesActorScopeBoundReferencesAndCursors() {
    var service = service(pagePort());

    ControlHistoryDetailResponse issued = service.detail(10, "", "", ACTOR);
    ControlHistoryDetailResponse first = service.detail(1, issued.detailRef(), "", ACTOR);
    ControlHistoryDetailResponse second = service.detail(1, "", first.nextCursor(), ACTOR);
    ControlHistoryDetailResponse third = service.detail(1, "", second.nextCursor(), ACTOR);

    assertThat(issued.state()).isEqualTo("REFERENCE_ISSUED");
    assertThat(issued.detailRef()).matches("[A-Za-z0-9_-]{22}");
    assertThat(issued.items()).isEmpty();
    assertThat(first.items())
        .extracting(ControlHistoryDetailResponse.Item::role)
        .containsExactly("USER");
    assertThat(second.items())
        .extracting(ControlHistoryDetailResponse.Item::role)
        .containsExactly("ASSISTANT");
    assertThat(third.items())
        .extracting(ControlHistoryDetailResponse.Item::role)
        .containsExactly("USER");
    assertThat(third.nextCursor()).isEmpty();
    assertThat(first.toString()).doesNotContain(issued.detailRef(), first.nextCursor());
    assertThatThrownBy(() -> service.detail(1, issued.detailRef(), "", ACTOR))
        .isInstanceOf(ControlHistoryDetailNotFoundException.class);
  }

  @Test
  void hidesMissingWrongActorAndScopeAsTheSameNotFoundOutcome() {
    var service = service(pagePort());
    String reference = service.detail(10, "", "", ACTOR).detailRef();

    assertThatThrownBy(() -> service.detail(10, reference, "", OTHER_ACTOR))
        .isInstanceOf(ControlHistoryDetailNotFoundException.class);
    assertThat(service.detail(10, reference, "", ACTOR).state()).isEqualTo("READY");
    assertThatThrownBy(() -> service.detail(10, "", "", OTHER_ACTOR))
        .isInstanceOf(ControlHistoryDetailNotFoundException.class);

    String pagedReference = service.detail(10, "", "", ACTOR).detailRef();
    String cursor = service.detail(1, pagedReference, "", ACTOR).nextCursor();
    assertThatThrownBy(() -> service.detail(1, "", cursor, OTHER_ACTOR))
        .isInstanceOf(ControlHistoryDetailNotFoundException.class);
    assertThat(service.detail(1, "", cursor, ACTOR).state()).isEqualTo("READY");
  }

  @Test
  void mapsSafePortFailuresAndClosedRuntimeToEmptyStableResponses() {
    var unavailable =
        service(
            (scope, request) -> {
              throw new HistorySnapshotUnavailableException();
            });
    String reference = unavailable.detail(10, "", "", ACTOR).detailRef();

    assertThat(unavailable.detail(10, reference, "", ACTOR))
        .extracting(
            ControlHistoryDetailResponse::state,
            ControlHistoryDetailResponse::code,
            ControlHistoryDetailResponse::items)
        .containsExactly("DEGRADED", "CONTROL_SNAPSHOT_UNAVAILABLE", List.of());

    var runtime = ControlPlaneStatusServiceTest.runtime();
    runtime.close();
    var closed = service(runtime, pagePort());
    assertThat(closed.detail(10, "", "", ACTOR))
        .extracting(
            ControlHistoryDetailResponse::state,
            ControlHistoryDetailResponse::code,
            ControlHistoryDetailResponse::items)
        .containsExactly("SHUTTING_DOWN", "CONTROL_SHUTTING_DOWN", List.of());
  }

  @Test
  void rejectsStateProjectionsThatCouldMixReferenceAndReadAuthority() {
    assertThatThrownBy(
            () ->
                new ControlHistoryDetailResponse(
                    ControlPlaneContract.CURRENT_VERSION,
                    NOW,
                    "READY",
                    "",
                    "A".repeat(22),
                    List.of(),
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ControlHistoryDetailService service(ControlHistorySnapshotPort port) {
    return service(ControlPlaneStatusServiceTest.runtime(), port);
  }

  private static ControlHistoryDetailService service(
      ControlPlaneRuntime runtime, ControlHistorySnapshotPort port) {
    return new ControlHistoryDetailService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        runtime,
        actor -> ACTOR.equals(actor) ? java.util.Optional.of(SCOPE) : java.util.Optional.empty(),
        port,
        sequentialRandom());
  }

  private static ControlHistorySnapshotPort pagePort() {
    List<HistoryDetailItem> items =
        List.of(
            new HistoryDetailItem(HistoryVisibleRole.USER, NOW.minusSeconds(1)),
            new HistoryDetailItem(HistoryVisibleRole.ASSISTANT, NOW.minusSeconds(2)),
            new HistoryDetailItem(HistoryVisibleRole.USER, NOW.minusSeconds(3)));
    return (scope, request) -> {
      int start = Math.min(request.offset(), items.size());
      int end = Math.min(start + request.pageSize(), items.size());
      return new HistoryDetailPage(items.subList(start, end), end < items.size());
    };
  }

  private static ControlRandomSource sequentialRandom() {
    var sequence = new AtomicInteger();
    return size -> {
      byte[] value = new byte[size];
      value[value.length - 1] = (byte) sequence.incrementAndGet();
      return value;
    };
  }
}
