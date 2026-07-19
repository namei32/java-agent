package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.control.HistoryDetailItem;
import io.namei.agent.kernel.control.HistoryDetailPage;
import io.namei.agent.kernel.control.HistoryScopeCapability;
import io.namei.agent.kernel.control.HistoryVisibleRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("failure")
class ControlHistoryDetailFailureTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
  private static final String ACTOR = "A".repeat(22);
  private static final HistoryScopeCapability SCOPE =
      HistoryScopeCapability.fromTrustedDigest("a".repeat(64));
  private static final HistoryDetailItem ITEM =
      new HistoryDetailItem(HistoryVisibleRole.USER, NOW.minusSeconds(1));

  @Test
  void concurrentReferenceAndCursorConsumptionPerformsExactlyOneReadEach() throws Exception {
    var clock = new MutableClock(NOW);
    var reads = new AtomicInteger();
    var service = service(clock, actor -> Optional.of(SCOPE), reads);

    String reference = service.detail(10, "", "", ACTOR).detailRef();
    Consumption referenceRace = race(() -> service.detail(10, reference, "", ACTOR));

    assertThat(referenceRace).isEqualTo(new Consumption(1, 1));
    assertThat(reads).hasValue(1);

    String cursorReference = service.detail(10, "", "", ACTOR).detailRef();
    String cursor = service.detail(10, cursorReference, "", ACTOR).nextCursor();
    reads.set(0);
    Consumption cursorRace = race(() -> service.detail(10, "", cursor, ACTOR));

    assertThat(cursorRace).isEqualTo(new Consumption(1, 1));
    assertThat(reads).hasValue(1);
  }

  @Test
  void expiryRevocationAndClosureReturnSafeOutcomesWithoutReadingAnyHistory() {
    var clock = new MutableClock(NOW);
    var allowed = new AtomicBoolean(true);
    var reads = new AtomicInteger();
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var service =
        service(
            clock, actor -> allowed.get() ? Optional.of(SCOPE) : Optional.empty(), reads, runtime);

    String expired = service.detail(10, "", "", ACTOR).detailRef();
    clock.advance(Duration.ofMinutes(1));
    assertThatThrownBy(() -> service.detail(10, expired, "", ACTOR))
        .isInstanceOf(ControlHistoryDetailNotFoundException.class);
    assertThat(reads).hasValue(0);

    String revoked = service.detail(10, "", "", ACTOR).detailRef();
    allowed.set(false);
    assertThatThrownBy(() -> service.detail(10, revoked, "", ACTOR))
        .isInstanceOf(ControlHistoryDetailNotFoundException.class);
    assertThat(reads).hasValue(0);

    allowed.set(true);
    String closed = service.detail(10, "", "", ACTOR).detailRef();
    runtime.close();
    assertThat(service.detail(10, closed, "", ACTOR))
        .extracting(
            ControlHistoryDetailResponse::state,
            ControlHistoryDetailResponse::code,
            ControlHistoryDetailResponse::items)
        .containsExactly("SHUTTING_DOWN", "CONTROL_SHUTTING_DOWN", List.of());
    assertThat(reads).hasValue(0);
  }

  private static Consumption race(DetailRead read) throws Exception {
    var successes = new AtomicInteger();
    var missing = new AtomicInteger();
    var unexpected = new AtomicReference<Throwable>();
    var ready = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    Thread first = start(ready, release, read, successes, missing, unexpected);
    Thread second = start(ready, release, read, successes, missing, unexpected);

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    first.join();
    second.join();
    assertThat(unexpected).hasValue(null);
    return new Consumption(successes.get(), missing.get());
  }

  private static Thread start(
      CountDownLatch ready,
      CountDownLatch release,
      DetailRead read,
      AtomicInteger successes,
      AtomicInteger missing,
      AtomicReference<Throwable> unexpected) {
    return Thread.ofVirtual()
        .start(
            () -> {
              ready.countDown();
              await(release);
              try {
                if ("READY".equals(read.get().state())) {
                  successes.incrementAndGet();
                } else {
                  unexpected.compareAndSet(null, new AssertionError("详情读取没有返回 READY"));
                }
              } catch (ControlHistoryDetailNotFoundException expected) {
                missing.incrementAndGet();
              } catch (RuntimeException failure) {
                unexpected.compareAndSet(null, failure);
              }
            });
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static ControlHistoryDetailService service(
      Clock clock, ControlHistoryScopeResolver scopes, AtomicInteger reads) {
    return service(clock, scopes, reads, ControlPlaneStatusServiceTest.runtime());
  }

  private static ControlHistoryDetailService service(
      Clock clock,
      ControlHistoryScopeResolver scopes,
      AtomicInteger reads,
      ControlPlaneRuntime runtime) {
    return new ControlHistoryDetailService(
        clock,
        runtime,
        scopes,
        (scope, request) -> {
          reads.incrementAndGet();
          return new HistoryDetailPage(List.of(ITEM), request.offset() == 0);
        },
        sequentialRandom());
  }

  private static ControlRandomSource sequentialRandom() {
    var sequence = new AtomicInteger();
    return size -> {
      byte[] value = new byte[size];
      value[value.length - 1] = (byte) sequence.incrementAndGet();
      return value;
    };
  }

  @FunctionalInterface
  private interface DetailRead {
    ControlHistoryDetailResponse get();
  }

  private record Consumption(int successful, int notFound) {}

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
