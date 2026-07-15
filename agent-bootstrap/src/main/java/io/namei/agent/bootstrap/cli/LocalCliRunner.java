package io.namei.agent.bootstrap.cli;

import io.namei.agent.application.BoundedOutboundBuffer;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.OutboundDeliveryException;
import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalCliRunner {
  private static final String SENDER_ID = "local-cli-user";

  private final MessageTurnService turns;
  private final CliProperties properties;
  private final Clock clock;
  private final CliIdGenerator ids;
  private final CliInput input;
  private final CliOutput output;
  private final CliThreadStarter threadStarter;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean shutdown = new AtomicBoolean();
  private final AtomicReference<BoundedOutboundBuffer> activeBuffer = new AtomicReference<>();

  public LocalCliRunner(
      MessageTurnService turns,
      CliProperties properties,
      Clock clock,
      CliIdGenerator ids,
      CliInput input,
      CliOutput output,
      CliThreadStarter threadStarter) {
    this.turns = Objects.requireNonNull(turns, "turns");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.input = Objects.requireNonNull(input, "input");
    this.output = Objects.requireNonNull(output, "output");
    this.threadStarter = Objects.requireNonNull(threadStarter, "threadStarter");
  }

  public void run() {
    if (shutdown.get()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      throw new CliRunnerException("CLI Runner 已经在运行");
    }
    try {
      while (!shutdown.get()) {
        String line = input.readLine();
        if (line == null) {
          return;
        }
        if (line.isBlank()) {
          continue;
        }
        runTurn(line);
      }
    } finally {
      running.set(false);
    }
  }

  public void shutdown() {
    shutdown.set(true);
    BoundedOutboundBuffer buffer = activeBuffer.get();
    if (buffer != null) {
      buffer.shutdown();
    }
  }

  public boolean isShutdown() {
    return shutdown.get();
  }

  private void runTurn(String content) {
    var inbound =
        new InboundMessage(
            MessageContract.CURRENT_VERSION,
            ids.newMessageId(),
            ids.newTurnId(),
            properties.sessionId(),
            new MessageRoute("cli", properties.conversationId()),
            SENDER_ID,
            content,
            clock.instant());
    var buffer =
        new BoundedOutboundBuffer(
            inbound, properties.bufferCapacity(), properties.publishTimeout());
    if (!activeBuffer.compareAndSet(null, buffer)) {
      throw new CliRunnerException("CLI 同时只能运行一个 Turn");
    }

    Thread producer = null;
    var producerFailure = new AtomicReference<Throwable>();
    try {
      if (shutdown.get()) {
        buffer.shutdown();
        return;
      }
      try {
        producer =
            Objects.requireNonNull(
                threadStarter.start(
                    "namei-cli-" + inbound.turnId(),
                    () -> {
                      try {
                        turns.process(inbound, buffer, buffer.cancellation());
                      } catch (Throwable failure) {
                        producerFailure.compareAndSet(null, failure);
                      }
                    }),
                "threadStarter 返回了 null");
      } catch (RuntimeException failure) {
        buffer.shutdown();
        throw new CliRunnerException("无法启动 CLI Turn", failure);
      }

      consumeTurn(buffer, producer, producerFailure);
    } catch (CliOutputException failure) {
      buffer.disconnect();
      if (producer != null) {
        awaitProducer(producer, buffer);
      }
      throw failure;
    } finally {
      activeBuffer.compareAndSet(buffer, null);
    }
  }

  private void consumeTurn(
      BoundedOutboundBuffer buffer, Thread producer, AtomicReference<Throwable> producerFailure) {
    var renderer = new CliMessageRenderer(output);
    while (!renderer.isTerminal()) {
      var next = buffer.poll(properties.pollTimeout());
      if (next.isPresent()) {
        renderer.accept(next.orElseThrow());
        continue;
      }
      if (shutdown.get()) {
        awaitProducer(producer, buffer);
        return;
      }
      if (!producer.isAlive()) {
        handleProducerExit(buffer, producerFailure.get());
        return;
      }
    }

    awaitProducer(producer, buffer);
    Throwable failure = producerFailure.get();
    if (failure != null) {
      handleProducerExit(buffer, failure);
    }
  }

  private void handleProducerExit(BoundedOutboundBuffer buffer, Throwable failure) {
    if (failure instanceof OutboundDeliveryException delivery) {
      switch (delivery.reason()) {
        case BACKPRESSURE_EXCEEDED -> {
          output.writeStderr(TurnCancellationCode.BACKPRESSURE_EXCEEDED.name() + "\n");
          return;
        }
        case CHANNEL_DISCONNECTED -> {
          output.writeStderr(TurnCancellationCode.CHANNEL_DISCONNECTED.name() + "\n");
          return;
        }
        case SHUTDOWN -> {
          if (shutdown.get()) {
            return;
          }
        }
        case INTERRUPTED -> {
          output.writeStderr(TurnCancellationCode.REQUESTED.name() + "\n");
          return;
        }
        case INVALID_MESSAGE -> {
          throw new CliRunnerException("CLI Turn 产生了无效消息");
        }
      }
    }
    if (failure == null && buffer.cancellation().isCancellationRequested()) {
      if (buffer.cancellation().reason() == TurnCancellationCode.SHUTDOWN && shutdown.get()) {
        return;
      }
      output.writeStderr(buffer.cancellation().reason().name() + "\n");
      return;
    }
    throw new CliRunnerException("CLI Turn 未产生有效终态");
  }

  private void awaitProducer(Thread producer, BoundedOutboundBuffer buffer) {
    try {
      if (producer.join(properties.pollTimeout())) {
        return;
      }
      buffer.shutdown();
      producer.interrupt();
      if (!producer.join(properties.pollTimeout())) {
        throw new CliRunnerException("CLI Turn 未能在期限内停止");
      }
    } catch (InterruptedException interrupted) {
      buffer.shutdown();
      producer.interrupt();
      Thread.currentThread().interrupt();
      throw new CliRunnerException("CLI Runner 等待被中断", interrupted);
    }
  }
}
