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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ControlHistoryReferenceStoreTest {
  private static final String ACTOR = "AAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void issuesStableOpaqueActorBoundShortLivedReferences() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-19T00:00:00Z"));
    ControlHistoryReferenceStore store =
        new ControlHistoryReferenceStore(clock, sequentialRandom(), Duration.ofMinutes(1), 2);
    ControlTerminalTurnSnapshot snapshot =
        new ControlTerminalTurnSnapshot(
            reference(99),
            "telegram",
            ControlTerminalKind.COMPLETED,
            Instant.parse("2026-07-19T00:00:00Z"));

    String reference = store.issue(ACTOR, snapshot);

    assertThat(reference).matches("[A-Za-z0-9_-]{22}");
    assertThat(store.issue(ACTOR, snapshot)).isEqualTo(reference);
    assertThat(reference).doesNotContain("telegram", ACTOR, snapshot.turnRef().value());
    assertThat(store.resolve(reference, "BBBBBBBBBBBBBBBBBBBBBB")).isEmpty();
    assertThat(store.resolve(reference, ACTOR)).contains(snapshot);

    clock.advance(Duration.ofMinutes(1));

    assertThat(store.resolve(reference, ACTOR)).isEmpty();
  }

  @Test
  void neverReusesTheCurrentInternalTurnReferenceWhenRandomValuesCollide() {
    ControlHistoryReferenceStore store =
        new ControlHistoryReferenceStore(
            Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
            sequentialRandom(),
            Duration.ofMinutes(1),
            2);
    ControlTerminalTurnSnapshot snapshot =
        new ControlTerminalTurnSnapshot(
            reference(1),
            "telegram",
            ControlTerminalKind.COMPLETED,
            Instant.parse("2026-07-19T00:00:00Z"));

    String issued = store.issue(ACTOR, snapshot);

    assertThat(issued).isNotEqualTo(snapshot.turnRef().value());
    assertThat(store.resolve(issued, ACTOR)).contains(snapshot);
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
