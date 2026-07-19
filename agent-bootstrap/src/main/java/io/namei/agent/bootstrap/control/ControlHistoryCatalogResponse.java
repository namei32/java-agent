package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlPlaneContract;
import io.namei.agent.kernel.control.ControlTerminalKind;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** The deliberately minimal, read-only R13-C2-A terminal history catalog projection. */
public record ControlHistoryCatalogResponse(
    int schemaVersion,
    Instant observedAt,
    String state,
    String code,
    List<Item> items,
    String nextCursor) {
  private static final Pattern STATE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern CODE = Pattern.compile("(?:|[A-Z][A-Z0-9_]{0,63})");
  private static final Pattern CURSOR = Pattern.compile("(?:|[A-Za-z0-9_-]{22})");

  public ControlHistoryCatalogResponse {
    if (schemaVersion != ControlPlaneContract.CURRENT_VERSION) {
      throw new IllegalArgumentException("控制历史目录 schemaVersion 无效");
    }
    observedAt = Objects.requireNonNull(observedAt, "observedAt");
    if (state == null || !STATE.matcher(state).matches()) {
      throw new IllegalArgumentException("控制历史目录状态无效");
    }
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("控制历史目录状态码无效");
    }
    items = List.copyOf(items);
    if (nextCursor == null || !CURSOR.matcher(nextCursor).matches()) {
      throw new IllegalArgumentException("控制历史目录游标无效");
    }
  }

  @Override
  public String toString() {
    return "ControlHistoryCatalogResponse[schemaVersion="
        + schemaVersion
        + ", observedAt="
        + observedAt
        + ", state="
        + state
        + ", code="
        + code
        + ", items="
        + items
        + ", nextCursor="
        + (nextCursor.isEmpty() ? "" : "<redacted>")
        + "]";
  }

  public record Item(String historyRef, String channel, String terminalState, Instant completedAt) {
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    public Item {
      if (historyRef == null || !REFERENCE.matcher(historyRef).matches()) {
        throw new IllegalArgumentException("控制历史目录引用无效");
      }
      if (channel == null || !CHANNEL.matcher(channel).matches()) {
        throw new IllegalArgumentException("控制历史目录 Channel 无效");
      }
      if (terminalState == null || !STATE.matcher(terminalState).matches()) {
        throw new IllegalArgumentException("控制历史目录终态无效");
      }
      try {
        ControlTerminalKind.valueOf(terminalState);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("控制历史目录终态无效", invalid);
      }
      completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    @Override
    public String toString() {
      return "Item[historyRef=<redacted>, channel="
          + channel
          + ", terminalState="
          + terminalState
          + ", completedAt="
          + completedAt
          + "]";
    }
  }
}
