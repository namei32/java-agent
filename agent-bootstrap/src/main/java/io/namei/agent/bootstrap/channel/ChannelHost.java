package io.namei.agent.bootstrap.channel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class ChannelHost implements AutoCloseable {
  private final List<Registration> registrations;
  private State state = State.NEW;

  public ChannelHost(List<? extends ChannelAdapter> adapters) {
    Objects.requireNonNull(adapters, "adapters");
    var names = new HashSet<String>();
    var registrations = new ArrayList<Registration>(adapters.size());
    for (ChannelAdapter adapter : adapters) {
      ChannelAdapter required = Objects.requireNonNull(adapter, "adapter");
      String name = required.name();
      new ChannelStatusSnapshot(name, ChannelState.NEW, "", 0, 0);
      if (!names.add(name)) {
        throw new IllegalArgumentException("Channel 名称重复");
      }
      registrations.add(new Registration(name, required));
    }
    this.registrations = List.copyOf(registrations);
  }

  public synchronized void start() {
    if (state != State.NEW) {
      throw new IllegalStateException("Channel Host 不能重复启动");
    }
    state = State.STARTING;
    for (Registration registration : registrations) {
      try {
        registration.adapter.start();
        registration.started = true;
      } catch (RuntimeException failure) {
        registration.override = ChannelStatusSnapshot.failed(registration.name, "START_FAILED");
        safeCleanupFailedStart(registration);
      }
    }
    state = State.RUNNING;
  }

  public synchronized State state() {
    return state;
  }

  public synchronized List<ChannelStatusSnapshot> snapshots() {
    return registrations.stream().map(this::safeSnapshot).toList();
  }

  @Override
  public synchronized void close() {
    if (state == State.STOPPED) {
      return;
    }
    if (state == State.NEW) {
      state = State.STOPPED;
      return;
    }
    state = State.STOPPING;
    for (Registration registration : registrations) {
      if (!registration.started) {
        continue;
      }
      try {
        registration.adapter.stopAccepting();
      } catch (RuntimeException failure) {
        registration.override =
            ChannelStatusSnapshot.failed(registration.name, "STOP_ACCEPTING_FAILED");
      }
    }
    for (int index = registrations.size() - 1; index >= 0; index--) {
      Registration registration = registrations.get(index);
      if (!registration.started) {
        continue;
      }
      try {
        registration.adapter.close();
      } catch (RuntimeException failure) {
        registration.override = ChannelStatusSnapshot.failed(registration.name, "CLOSE_FAILED");
      }
    }
    state = State.STOPPED;
  }

  private void safeCleanupFailedStart(Registration registration) {
    try {
      registration.adapter.close();
    } catch (RuntimeException failure) {
      registration.override =
          ChannelStatusSnapshot.failed(registration.name, "START_CLEANUP_FAILED");
    }
  }

  private ChannelStatusSnapshot safeSnapshot(Registration registration) {
    if (registration.override != null) {
      return registration.override;
    }
    try {
      ChannelStatusSnapshot snapshot =
          Objects.requireNonNull(registration.adapter.snapshot(), "snapshot");
      if (!registration.name.equals(snapshot.name())) {
        return ChannelStatusSnapshot.failed(registration.name, "SNAPSHOT_INVALID");
      }
      return snapshot;
    } catch (RuntimeException failure) {
      return ChannelStatusSnapshot.failed(registration.name, "SNAPSHOT_FAILED");
    }
  }

  public enum State {
    NEW,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED
  }

  private static final class Registration {
    private final String name;
    private final ChannelAdapter adapter;
    private boolean started;
    private ChannelStatusSnapshot override;

    private Registration(String name, ChannelAdapter adapter) {
      this.name = name;
      this.adapter = adapter;
    }
  }
}
