package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.control.ControlTerminalTurnSnapshot;
import io.namei.agent.kernel.control.ControlTerminalKind;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ControlHistoryCursorStoreTest {
  private static final String ACTOR = "AAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void keepsOnlyOpaqueActorBoundShortLivedOneTimeTerminalMetadataPages() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-19T00:00:00Z"));
    ControlHistoryCursorStore store =
        new ControlHistoryCursorStore(clock, sequentialRandom(), Duration.ofMinutes(1), 2);
    List<ControlTerminalTurnSnapshot> remaining =
        List.of(
            new ControlTerminalTurnSnapshot(
                reference(1),
                "telegram",
                ControlTerminalKind.COMPLETED,
                Instant.parse("2026-07-19T00:00:00Z")));

    String cursor = store.issue(ACTOR, remaining);

    assertThat(cursor).matches("[A-Za-z0-9_-]{22}");
    assertThat(cursor).doesNotContain("telegram", ACTOR, "raw-session-secret");
    assertThat(store.take(cursor, "BBBBBBBBBBBBBBBBBBBBBB")).isEmpty();
    assertThat(store.take(cursor, ACTOR)).contains(remaining);
    assertThat(store.take(cursor, ACTOR)).isEmpty();

    String expired = store.issue(ACTOR, remaining);
    clock.advance(Duration.ofMinutes(1));
    assertThat(store.take(expired, ACTOR)).isEmpty();
  }

  private static ControlRandomSource sequentialRandom() {
    AtomicInteger next = new AtomicInteger();
    return size -> {
      byte[] value = new byte[size];
      value[value.length - 1] = (byte) next.incrementAndGet();
      return value;
    };
  }

  private static ControlTurnRef reference(int value) {
    byte[] bytes = new byte[16];
    bytes[15] = (byte) value;
    return ControlTurnRef.fromBytes(bytes);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
