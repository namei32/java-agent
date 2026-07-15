package io.namei.agent.adapter.mcp;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One bounded SDK client and stdio process for a single statically configured server. */
final class McpStdioClient implements AutoCloseable {
  private static final Consumer<JSONRPCMessage> NOOP_OBSERVER = ignored -> {};

  private final McpSettings settings;
  private final BoundedStdioClientTransport transport;
  private final McpSdkGateway gateway;
  private final Semaphore callPermits;
  private final AtomicBoolean acceptingCalls = new AtomicBoolean(true);

  McpStdioClient(McpServerDefinition server, McpSettings settings) {
    this(server, settings, NOOP_OBSERVER);
  }

  McpStdioClient(
      McpServerDefinition server, McpSettings settings, Consumer<JSONRPCMessage> messageObserver) {
    Objects.requireNonNull(server, "server");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.transport = new BoundedStdioClientTransport(server, settings, messageObserver);
    McpAsyncClient client =
        McpClient.async(transport)
            .requestTimeout(settings.requestTimeout())
            .initializationTimeout(settings.connectTimeout())
            .capabilities(McpSchema.ClientCapabilities.builder().build())
            .clientInfo(McpSchema.Implementation.builder("namei-agent-mcp-client", "0.1.0").build())
            .build();
    this.gateway = new McpSdkGateway(client);
    this.callPermits = new Semaphore(settings.maxConcurrentCallsPerServer(), true);
  }

  List<McpRemoteTool> initializeAndDiscover() {
    try {
      McpSchema.InitializeResult initialization =
          await(gateway.initialize().toFuture(), settings.connectTimeout());
      if (!ProtocolVersions.MCP_2025_11_25.equals(initialization.protocolVersion())
          || initialization.capabilities().tools() == null) {
        throw unavailable();
      }

      McpCatalogAccumulator catalog =
          new McpCatalogAccumulator(settings.maxListPages(), settings.maxToolsPerServer());
      String cursor = null;
      do {
        McpSchema.ListToolsResult page =
            await(gateway.listTools(cursor).toFuture(), settings.requestTimeout());
        List<McpRemoteTool> tools = new ArrayList<>(page.tools().size());
        for (McpSchema.Tool tool : page.tools()) {
          Boolean readOnlyHint =
              tool.annotations() == null ? null : tool.annotations().readOnlyHint();
          tools.add(
              new McpRemoteTool(tool.name(), tool.description(), tool.inputSchema(), readOnlyHint));
        }
        catalog.addPage(cursor, page.nextCursor(), tools);
        cursor = page.nextCursor();
      } while (cursor != null);
      return catalog.finish();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException | TimeoutException | RuntimeException exception) {
      throw unavailable();
    }
  }

  McpCallOutcome call(String remoteName, Map<String, Object> arguments) {
    Objects.requireNonNull(remoteName, "remoteName");
    Objects.requireNonNull(arguments, "arguments");
    if (!acceptingCalls.get()) {
      return McpCallOutcome.unavailable();
    }
    long deadline = deadline(settings.requestTimeout());
    boolean permit = false;
    McpCallHandle handle = null;
    CompletableFuture<McpSchema.CallToolResult> future = null;
    try {
      long remaining = remaining(deadline);
      if (remaining <= 0 || !callPermits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
        return McpCallOutcome.timeout();
      }
      permit = true;
      if (!acceptingCalls.get()) {
        return McpCallOutcome.unavailable();
      }
      handle = transport.newCallHandle();
      McpCallHandle currentHandle = handle;
      future =
          transport.withCallHandle(
              handle, () -> gateway.callTool(remoteName, arguments).toFuture());
      remaining = remaining(deadline);
      if (remaining <= 0) {
        handle.cancel();
        future.cancel(true);
        return McpCallOutcome.timeout();
      }
      McpSchema.CallToolResult result = future.get(remaining, TimeUnit.NANOSECONDS);
      if (!currentHandle.complete()) {
        throw cancelled();
      }
      return convert(result);
    } catch (InterruptedException exception) {
      if (handle != null) {
        handle.cancel();
      }
      if (future != null) {
        future.cancel(true);
      }
      Thread.currentThread().interrupt();
      throw cancelled();
    } catch (TimeoutException exception) {
      if (handle != null) {
        handle.cancel();
      }
      if (future != null) {
        future.cancel(true);
      }
      return transport.failed() ? McpCallOutcome.unavailable() : McpCallOutcome.timeout();
    } catch (ExecutionException exception) {
      if (handle != null && handle.isCancelled()) {
        throw cancelled();
      }
      if (containsTimeout(exception)) {
        if (handle != null) {
          handle.cancel();
        }
        return transport.failed() ? McpCallOutcome.unavailable() : McpCallOutcome.timeout();
      }
      return McpCallOutcome.unavailable();
    } catch (RuntimeException exception) {
      if (exception instanceof McpCallCancelledException) {
        throw exception;
      }
      return McpCallOutcome.unavailable();
    } finally {
      if (handle != null) {
        transport.retire(handle);
      }
      if (permit) {
        callPermits.release();
      }
    }
  }

  ProcessHandle processHandle() {
    return transport.processHandle();
  }

  @Override
  public void close() {
    if (acceptingCalls.compareAndSet(true, false)) {
      transport.cancelActiveCalls();
    }
    gateway.close();
    transport.close();
  }

  private static McpCallOutcome convert(McpSchema.CallToolResult result) {
    if (Boolean.TRUE.equals(result.isError())) {
      return McpCallOutcome.remoteError();
    }
    if (result.structuredContent() != null) {
      return McpCallOutcome.unsupportedResult();
    }
    List<String> text = new ArrayList<>(result.content().size());
    for (McpSchema.Content content : result.content()) {
      if (!(content instanceof McpSchema.TextContent textContent)) {
        return McpCallOutcome.unsupportedResult();
      }
      text.add(textContent.text());
    }
    return McpCallOutcome.success(String.join("\n", text));
  }

  private static <T> T await(CompletableFuture<T> future, Duration timeout)
      throws InterruptedException, ExecutionException, TimeoutException {
    return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  private static long deadline(Duration timeout) {
    long now = System.nanoTime();
    try {
      return Math.addExact(now, timeout.toNanos());
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
  }

  private static long remaining(long deadline) {
    return deadline - System.nanoTime();
  }

  private static boolean containsTimeout(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof TimeoutException) {
        return true;
      }
    }
    return false;
  }

  private static McpClientException unavailable() {
    return new McpClientException();
  }

  private static McpCallCancelledException cancelled() {
    return new McpCallCancelledException();
  }
}
