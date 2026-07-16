package io.namei.agent.bootstrap.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelHostTest {
  @Test
  void startsAdaptersInOrderAndExposesSafeSnapshots() {
    var events = new ArrayList<String>();
    var first = adapter("first", events);
    var second = adapter("second", events);
    var host = new ChannelHost(List.of(first, second));

    assertThat(host.state()).isEqualTo(ChannelHost.State.NEW);

    host.start();

    assertThat(events).containsExactly("start:first", "start:second");
    assertThat(host.state()).isEqualTo(ChannelHost.State.RUNNING);
    assertThat(host.snapshots())
        .extracting(ChannelStatusSnapshot::name)
        .containsExactly("first", "second");
    assertThat(host.snapshots())
        .extracting(ChannelStatusSnapshot::state)
        .containsExactly(ChannelState.RUNNING, ChannelState.RUNNING);
  }

  @Test
  void cleansFailedStartAndContinuesStartingOtherAdapters() {
    var events = new ArrayList<String>();
    var first = adapter("first", events);
    var failed = adapter("failed", events).failStart("token=secret");
    var third = adapter("third", events);
    var host = new ChannelHost(List.of(first, failed, third));

    host.start();

    assertThat(events)
        .containsExactly("start:first", "start:failed", "close:failed", "start:third");
    assertThat(host.snapshots().get(1))
        .isEqualTo(ChannelStatusSnapshot.failed("failed", "START_FAILED"));
    assertThat(host.snapshots().toString()).doesNotContain("secret");

    host.close();

    assertThat(events)
        .containsExactly(
            "start:first",
            "start:failed",
            "close:failed",
            "start:third",
            "stop:first",
            "stop:third",
            "close:third",
            "close:first");
  }

  @Test
  void stopsAcceptingInOrderAndClosesInReverseDespiteFailures() {
    var events = new ArrayList<String>();
    var first = adapter("first", events).failClose("close secret");
    var second = adapter("second", events).failStop("stop secret");
    var third = adapter("third", events);
    var host = new ChannelHost(List.of(first, second, third));
    host.start();
    events.clear();

    host.close();

    assertThat(events)
        .containsExactly(
            "stop:first",
            "stop:second",
            "stop:third",
            "close:third",
            "close:second",
            "close:first");
    assertThat(host.state()).isEqualTo(ChannelHost.State.STOPPED);
    assertThat(host.snapshots().toString()).doesNotContain("secret");
  }

  @Test
  void rejectsRepeatedStartAndTreatsCloseAsIdempotent() {
    var events = new ArrayList<String>();
    var host = new ChannelHost(List.of(adapter("only", events)));
    host.start();

    assertThatThrownBy(host::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("only");

    host.close();
    host.close();

    assertThat(events).containsExactly("start:only", "stop:only", "close:only");
  }

  @Test
  void emptyHostRunsWithoutAdaptersOrBackgroundWork() {
    var host = new ChannelHost(List.of());

    host.start();

    assertThat(host.state()).isEqualTo(ChannelHost.State.RUNNING);
    assertThat(host.snapshots()).isEmpty();

    host.close();

    assertThat(host.state()).isEqualTo(ChannelHost.State.STOPPED);
  }

  @Test
  void rejectsDuplicateOrInvalidAdapterNames() {
    var events = new ArrayList<String>();

    assertThatThrownBy(
            () ->
                new ChannelHost(
                    List.of(adapter("duplicate", events), adapter("duplicate", events))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChannelHost(List.of(adapter("Telegram", events))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static StubAdapter adapter(String name, List<String> events) {
    return new StubAdapter(name, events);
  }

  private static final class StubAdapter implements ChannelAdapter {
    private final String name;
    private final List<String> events;
    private String startFailure;
    private String stopFailure;
    private String closeFailure;
    private ChannelState state = ChannelState.NEW;

    private StubAdapter(String name, List<String> events) {
      this.name = name;
      this.events = events;
    }

    private StubAdapter failStart(String message) {
      startFailure = message;
      return this;
    }

    private StubAdapter failStop(String message) {
      stopFailure = message;
      return this;
    }

    private StubAdapter failClose(String message) {
      closeFailure = message;
      return this;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public void start() {
      events.add("start:" + name);
      if (startFailure != null) {
        throw new IllegalStateException(startFailure);
      }
      state = ChannelState.RUNNING;
    }

    @Override
    public void stopAccepting() {
      events.add("stop:" + name);
      if (stopFailure != null) {
        throw new IllegalStateException(stopFailure);
      }
      state = ChannelState.STOPPING;
    }

    @Override
    public ChannelStatusSnapshot snapshot() {
      return new ChannelStatusSnapshot(name, state, "", 0, 0);
    }

    @Override
    public void close() {
      events.add("close:" + name);
      state = ChannelState.STOPPED;
      if (closeFailure != null) {
        throw new IllegalStateException(closeFailure);
      }
    }
  }
}
