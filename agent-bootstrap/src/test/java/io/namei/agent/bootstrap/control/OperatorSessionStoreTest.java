package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperatorSessionStoreTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void createsDigestOnlyBearerAuthenticatesAndRevokesWithoutTokenOracle() {
    var clock = new MutableClock(NOW);
    var revokedActors = new ArrayList<String>();
    var store =
        new OperatorSessionStore(
            clock, new SequentialRandom(), Duration.ofMinutes(15), 2, revokedActors::add);

    OperatorSessionCreated created = store.create();

    assertThat(created.accessToken()).hasSize(43).doesNotContain("=");
    assertThat(created.tokenType()).isEqualTo("Bearer");
    assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    OperatorSessionPrincipal principal = store.authenticate(created.accessToken()).orElseThrow();
    assertThat(principal.actorRef()).hasSize(22);
    assertThat(store.toString()).doesNotContain(created.accessToken(), principal.actorRef());
    assertThat(store.authenticate("A".repeat(43))).isEmpty();

    assertThat(store.revoke(principal.actorRef())).isTrue();
    assertThat(store.revoke(principal.actorRef())).isFalse();
    assertThat(store.authenticate(created.accessToken())).isEmpty();
    assertThat(revokedActors).containsExactly(principal.actorRef());
  }

  @Test
  void enforcesCapacityThenLazilyExpiresAndClosesActorSubscriptions() {
    var clock = new MutableClock(NOW);
    var revokedActors = new ArrayList<String>();
    var store =
        new OperatorSessionStore(
            clock, new SequentialRandom(), Duration.ofMinutes(1), 1, revokedActors::add);
    OperatorSessionCreated first = store.create();
    String firstActor = store.authenticate(first.accessToken()).orElseThrow().actorRef();

    assertThatThrownBy(store::create).isInstanceOf(OperatorSessionCapacityException.class);

    clock.advance(Duration.ofMinutes(1));
    OperatorSessionCreated second = store.create();
    assertThat(store.authenticate(first.accessToken())).isEmpty();
    assertThat(store.authenticate(second.accessToken())).isPresent();
    assertThat(revokedActors).containsExactly(firstActor);

    store.close();
    assertThat(store.size()).isZero();
    assertThatThrownBy(store::create).isInstanceOf(IllegalStateException.class);
  }

  static final class SequentialRandom implements ControlRandomSource {
    private final AtomicInteger next = new AtomicInteger(1);

    @Override
    public byte[] nextBytes(int size) {
      byte[] value = new byte[size];
      java.util.Arrays.fill(value, (byte) next.getAndIncrement());
      return value;
    }
  }

  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
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
