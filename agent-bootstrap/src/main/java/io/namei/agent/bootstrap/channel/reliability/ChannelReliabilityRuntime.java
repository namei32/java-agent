package io.namei.agent.bootstrap.channel.reliability;

import io.namei.agent.adapter.sqlite.ChannelLedgerSchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcChannelLedger;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureCarrier;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureKind;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChannelReliabilityRuntime {
  private static final int SQLITE_BUSY_TIMEOUT_MILLIS = 5_000;

  private final Path database;
  private final ChannelReliabilityProperties properties;
  private final Clock clock;
  private final String ownerId;
  private final AtomicBoolean open = new AtomicBoolean();

  public ChannelReliabilityRuntime(
      Path workspace, ChannelReliabilityProperties properties, Clock clock) {
    this(workspace, properties, clock, secureOwnerId());
  }

  ChannelReliabilityRuntime(
      Path workspace, ChannelReliabilityProperties properties, Clock clock, String ownerId) {
    Objects.requireNonNull(workspace, "workspace");
    this.properties = Objects.requireNonNull(properties, "properties");
    if (properties.mode() != ChannelReliabilityMode.SQLITE) {
      throw new IllegalArgumentException("只有 SQLITE 模式可以构造渠道可靠性运行时");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ownerId = requireOwner(ownerId);
    this.database =
        workspace.toAbsolutePath().normalize().resolve("channels").resolve("channel-ledger.db");
  }

  public Session start(ChannelInstanceId instance) {
    Objects.requireNonNull(instance, "instance");
    if (!open.compareAndSet(false, true)) {
      throw new IllegalStateException("渠道可靠性运行时已经启动");
    }
    try {
      var schema = new ChannelLedgerSchemaInitializer(database, SQLITE_BUSY_TIMEOUT_MILLIS);
      schema.initialize();
      ChannelLedgerPort ledger = new JdbcChannelLedger(schema);
      recoverFully(ledger, instance);
      ChannelLedgerResult.Cleanup cleanup = cleanup(ledger, instance);
      ChannelLedgerPort guarded = new CapacityGuardedLedger(ledger, instance);
      ChannelLedgerResult.Snapshot snapshot = ledger.snapshot(instance);
      return new Session(guarded, ownerId, snapshot, cleanup.capacityAvailable(), this::release);
    } catch (RuntimeException | Error failure) {
      open.set(false);
      throw failure;
    }
  }

  public Clock clock() {
    return clock;
  }

  public ChannelReliabilityProperties properties() {
    return properties;
  }

  private void recoverFully(ChannelLedgerPort ledger, ChannelInstanceId instance) {
    boolean remaining;
    do {
      ChannelLedgerResult.Recovery recovered =
          Objects.requireNonNull(
              ledger.recover(
                  new ChannelLedgerCommand.Recover(
                      instance, ownerId, clock.instant(), properties.recoveryBatchSize())),
              "ledger recovery");
      remaining = recovered.remaining();
    } while (remaining);
  }

  private ChannelLedgerResult.Cleanup cleanup(
      ChannelLedgerPort ledger, ChannelInstanceId instance) {
    Instant now = clock.instant();
    return Objects.requireNonNull(
        ledger.cleanup(
            new ChannelLedgerCommand.Cleanup(
                instance,
                now.minus(properties.retention()),
                now,
                properties.cleanupBatchSize(),
                properties.maxInboxRecords(),
                properties.maxDeliveryRecords())),
        "ledger cleanup");
  }

  private void release() {
    open.set(false);
  }

  private static String secureOwnerId() {
    byte[] value = new byte[16];
    new SecureRandom().nextBytes(value);
    return HexFormat.of().formatHex(value);
  }

  private static String requireOwner(String value) {
    if (value == null || !value.matches("[0-9a-f]{32}")) {
      throw new IllegalArgumentException("渠道 Owner ID 格式无效");
    }
    return value;
  }

  public static final class Session implements AutoCloseable {
    private final ChannelLedgerPort ledger;
    private final String ownerId;
    private final ChannelLedgerResult.Snapshot snapshot;
    private final boolean capacityAvailable;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Session(
        ChannelLedgerPort ledger,
        String ownerId,
        ChannelLedgerResult.Snapshot snapshot,
        boolean capacityAvailable,
        Runnable release) {
      this.ledger = Objects.requireNonNull(ledger, "ledger");
      this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
      this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
      this.capacityAvailable = capacityAvailable;
      this.release = Objects.requireNonNull(release, "release");
    }

    public ChannelLedgerPort ledger() {
      return ledger;
    }

    public String ownerId() {
      return ownerId;
    }

    public ChannelLedgerResult.Snapshot snapshot() {
      return snapshot;
    }

    public boolean capacityAvailable() {
      return capacityAvailable;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        release.run();
      }
    }
  }

  private final class CapacityGuardedLedger implements ChannelLedgerPort {
    private final ChannelLedgerPort delegate;
    private final ChannelInstanceId instance;

    private CapacityGuardedLedger(ChannelLedgerPort delegate, ChannelInstanceId instance) {
      this.delegate = delegate;
      this.instance = instance;
    }

    @Override
    public ChannelLedgerResult.Event recordEvent(ChannelLedgerCommand.RecordEvent command) {
      if (!instance.equals(command.instance())) {
        throw new CapacityFailure();
      }
      if (!ChannelReliabilityRuntime.this.cleanup(delegate, instance).capacityAvailable()) {
        throw new CapacityFailure();
      }
      return delegate.recordEvent(command);
    }

    @Override
    public ChannelLedgerResult.TurnStart startTurn(ChannelLedgerCommand.StartTurn command) {
      return delegate.startTurn(command);
    }

    @Override
    public ChannelLedgerResult.Terminal recordTerminal(
        ChannelLedgerCommand.RecordTerminal command) {
      return delegate.recordTerminal(command);
    }

    @Override
    public Optional<ChannelLedgerResult.DeliveryWork> claimNextDelivery(
        ChannelLedgerCommand.ClaimDelivery command) {
      return delegate.claimNextDelivery(command);
    }

    @Override
    public ChannelLedgerResult.DeliveryUpdate recordDeliveryOutcome(
        ChannelLedgerCommand.RecordDeliveryOutcome command) {
      return delegate.recordDeliveryOutcome(command);
    }

    @Override
    public ChannelLedgerResult.Recovery recover(ChannelLedgerCommand.Recover command) {
      return delegate.recover(command);
    }

    @Override
    public ChannelLedgerResult.Cleanup cleanup(ChannelLedgerCommand.Cleanup command) {
      return delegate.cleanup(command);
    }

    @Override
    public ChannelLedgerResult.Snapshot snapshot(ChannelInstanceId requested) {
      return delegate.snapshot(requested);
    }
  }

  private static final class CapacityFailure extends RuntimeException
      implements ChannelLedgerFailureCarrier {
    private CapacityFailure() {
      super("渠道账本容量已满", null, false, false);
    }

    @Override
    public ChannelLedgerFailureKind ledgerFailureKind() {
      return ChannelLedgerFailureKind.CAPACITY_EXCEEDED;
    }
  }
}
