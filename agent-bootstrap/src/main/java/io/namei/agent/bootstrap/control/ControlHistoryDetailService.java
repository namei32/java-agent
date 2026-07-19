package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlPlaneContract;
import io.namei.agent.kernel.control.ControlStableCode;
import io.namei.agent.kernel.control.HistoryDetailPage;
import io.namei.agent.kernel.control.HistoryDetailReadRequest;
import io.namei.agent.kernel.control.HistoryDetailRef;
import io.namei.agent.kernel.control.HistoryPageCursor;
import io.namei.agent.kernel.control.HistoryScopeCapability;
import io.namei.agent.kernel.control.HistorySnapshotUnavailableException;
import io.namei.agent.kernel.port.ControlHistorySnapshotPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Orchestrates only actor/Scope-bound, zero-content detail reference and cursor consumption. */
public final class ControlHistoryDetailService {
  private final Clock clock;
  private final ControlPlaneRuntime runtime;
  private final ControlHistoryScopeResolver scopes;
  private final ControlHistorySnapshotPort snapshots;
  private final ControlHistoryDetailReferenceStore references;
  private final ControlHistoryDetailCursorStore cursors;

  ControlHistoryDetailService(
      Clock clock,
      ControlPlaneRuntime runtime,
      ControlHistoryScopeResolver scopes,
      ControlHistorySnapshotPort snapshots,
      ControlRandomSource random) {
    this(
        clock,
        runtime,
        scopes,
        snapshots,
        new ControlHistoryDetailReferenceStore(clock, random),
        new ControlHistoryDetailCursorStore(clock, random));
  }

  ControlHistoryDetailService(
      Clock clock,
      ControlPlaneRuntime runtime,
      ControlHistoryScopeResolver scopes,
      ControlHistorySnapshotPort snapshots,
      ControlHistoryDetailReferenceStore references,
      ControlHistoryDetailCursorStore cursors) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.scopes = Objects.requireNonNull(scopes, "scopes");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.references = Objects.requireNonNull(references, "references");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  public ControlHistoryDetailResponse detail(
      int pageSize, String rawReference, String rawCursor, String actorRef) {
    requireRequest(pageSize, rawReference, rawCursor);
    Instant now = clock.instant();
    if (runtime.isClosed()) {
      return response(
          now, "SHUTTING_DOWN", ControlStableCode.CONTROL_SHUTTING_DOWN.name(), "", List.of(), "");
    }
    if (rawReference.isEmpty() && rawCursor.isEmpty()) {
      return issue(now, actorRef);
    }
    if (!rawReference.isEmpty()) {
      HistoryDetailRef reference = HistoryDetailRef.parse(rawReference);
      ControlHistoryDetailReferenceStore.Entry entry =
          references
              .take(reference, actorRef)
              .orElseThrow(ControlHistoryDetailNotFoundException::new);
      return read(now, pageSize, actorRef, entry.scope(), reference, 0, now);
    }
    HistoryPageCursor cursor = HistoryPageCursor.parse(rawCursor);
    ControlHistoryDetailCursorStore.Entry entry =
        cursors.take(cursor, actorRef).orElseThrow(ControlHistoryDetailNotFoundException::new);
    return read(
        now,
        pageSize,
        actorRef,
        entry.scope(),
        entry.reference(),
        entry.offset(),
        entry.observedAt());
  }

  private ControlHistoryDetailResponse issue(Instant now, String actorRef) {
    HistoryScopeCapability scope = currentScope(actorRef);
    HistoryDetailRef reference = references.issue(actorRef, scope);
    return response(now, "REFERENCE_ISSUED", "", reference.value(), List.of(), "");
  }

  private ControlHistoryDetailResponse read(
      Instant now,
      int pageSize,
      String actorRef,
      HistoryScopeCapability expectedScope,
      HistoryDetailRef reference,
      int offset,
      Instant observedAt) {
    if (!currentScope(actorRef).equals(expectedScope)) {
      throw new ControlHistoryDetailNotFoundException();
    }
    try {
      HistoryDetailPage page =
          snapshots.read(
              expectedScope, new HistoryDetailReadRequest(reference, pageSize, offset, observedAt));
      if (page.hasMore() && page.items().isEmpty()) {
        throw new HistorySnapshotUnavailableException();
      }
      int nextOffset = Math.addExact(offset, page.items().size());
      if (page.hasMore() && nextOffset >= HistoryDetailReadRequest.MAXIMUM_OFFSET) {
        throw new HistorySnapshotUnavailableException();
      }
      String nextCursor =
          page.hasMore()
              ? cursors.issue(actorRef, expectedScope, reference, nextOffset, observedAt).value()
              : "";
      List<ControlHistoryDetailResponse.Item> items =
          page.items().stream()
              .map(
                  item ->
                      new ControlHistoryDetailResponse.Item(item.role().name(), item.occurredAt()))
              .toList();
      return response(now, "READY", "", "", items, nextCursor);
    } catch (HistorySnapshotUnavailableException | ArithmeticException unavailable) {
      return response(
          now,
          "DEGRADED",
          ControlStableCode.CONTROL_SNAPSHOT_UNAVAILABLE.name(),
          "",
          List.of(),
          "");
    }
  }

  private HistoryScopeCapability currentScope(String actorRef) {
    return scopes.resolve(actorRef).orElseThrow(ControlHistoryDetailNotFoundException::new);
  }

  private static ControlHistoryDetailResponse response(
      Instant observedAt,
      String state,
      String code,
      String detailRef,
      List<ControlHistoryDetailResponse.Item> items,
      String nextCursor) {
    return new ControlHistoryDetailResponse(
        ControlPlaneContract.CURRENT_VERSION,
        observedAt,
        state,
        code,
        detailRef,
        items,
        nextCursor);
  }

  private static void requireRequest(int pageSize, String reference, String cursor) {
    if (pageSize < 1 || pageSize > 20 || reference == null || cursor == null) {
      throw new IllegalArgumentException("控制历史详情请求无效");
    }
    if (!reference.isEmpty() && !cursor.isEmpty()) {
      throw new IllegalArgumentException("控制历史详情引用和游标不能并用");
    }
  }
}
