package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpCancellationIT {
  @TempDir Path temp;

  @Test
  void translatesThreadInterruptionToWireCancellationAndDropsLateResponse() throws Exception {
    LinkedBlockingQueue<McpSchema.JSONRPCNotification> notifications = new LinkedBlockingQueue<>();
    McpStdioClient client =
        new McpStdioClient(
            McpReferenceServerSupport.server(temp, "normal"),
            McpReferenceServerSupport.settings(temp, 1_048_576, 1),
            message -> {
              if (message instanceof McpSchema.JSONRPCNotification notification) {
                notifications.add(notification);
              }
            });
    AtomicReference<McpCallOutcome> delivered = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interruptPreserved = new AtomicBoolean();
    try {
      client.initializeAndDiscover();
      notifications.clear();
      Thread caller =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      delivered.set(client.call("slow", Map.of()));
                    } catch (Throwable throwable) {
                      failure.set(throwable);
                      interruptPreserved.set(Thread.currentThread().isInterrupted());
                    }
                  });

      McpSchema.JSONRPCNotification started =
          takeNotification(notifications, "notifications/test/call_started");
      assertThat(started).isNotNull();
      caller.interrupt();
      caller.join(3_000);

      assertThat(caller.isAlive()).isFalse();
      assertThat(failure.get()).isInstanceOf(McpCallCancelledException.class);
      assertThat(interruptPreserved).isTrue();
      assertThat(delivered).hasValue(null);

      McpSchema.JSONRPCNotification observed =
          takeNotification(notifications, "notifications/test/cancel_observed");
      assertThat(observed).isNotNull();
      assertThat(observed.params()).isInstanceOf(Map.class);
      assertThat(((Map<?, ?>) observed.params()).get("matches")).isEqualTo(true);

      Thread.sleep(150);
      assertThat(delivered).hasValue(null);
    } finally {
      client.close();
    }
    assertThat(client.processHandle().isAlive()).isFalse();
  }

  private static McpSchema.JSONRPCNotification takeNotification(
      LinkedBlockingQueue<McpSchema.JSONRPCNotification> notifications, String method)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      McpSchema.JSONRPCNotification next = notifications.poll(100, TimeUnit.MILLISECONDS);
      if (next != null && method.equals(next.method())) {
        return next;
      }
    }
    return null;
  }
}
