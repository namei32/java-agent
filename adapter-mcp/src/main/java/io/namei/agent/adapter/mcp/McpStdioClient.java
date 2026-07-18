package io.namei.agent.adapter.mcp;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.namei.agent.kernel.mcp.McpAssetCatalog;
import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import io.namei.agent.kernel.mcp.McpAssetDescriptor;
import io.namei.agent.kernel.mcp.McpAssetKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    return initializeAndDiscover(McpAssetCatalogMode.DISABLED, "unused").tools();
  }

  McpStartupCatalog initializeAndDiscover(McpAssetCatalogMode assetsMode, String serverId) {
    Objects.requireNonNull(assetsMode, "assetsMode");
    Objects.requireNonNull(serverId, "serverId");
    try {
      McpSchema.InitializeResult initialization =
          await(gateway.initialize().toFuture(), settings.connectTimeout());
      if (!ProtocolVersions.MCP_2025_11_25.equals(initialization.protocolVersion())
          || initialization.capabilities().tools() == null) {
        throw unavailable();
      }

      McpAssetCatalog assets =
          assetsMode == McpAssetCatalogMode.CATALOG_ONLY
              ? discoverAssets(initialization, serverId)
              : McpAssetCatalog.empty();
      return new McpStartupCatalog(discoverTools(), assets);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException | TimeoutException | RuntimeException exception) {
      throw unavailable();
    }
  }

  McpAssetCatalog initializeAndDiscoverAssets(String serverId) {
    Objects.requireNonNull(serverId, "serverId");
    try {
      McpSchema.InitializeResult initialization =
          await(gateway.initialize().toFuture(), settings.connectTimeout());
      if (!ProtocolVersions.MCP_2025_11_25.equals(initialization.protocolVersion())) {
        throw unavailable();
      }
      return discoverAssets(initialization, serverId);
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

  private void discoverResources(String serverId, List<McpAssetDescriptor> assets)
      throws InterruptedException, ExecutionException, TimeoutException {
    String cursor = null;
    Set<String> cursors = new HashSet<>();
    int discovered = 0;
    for (int page = 0; ; page++) {
      if (page >= settings.maxListPages() || !cursors.add(cursor == null ? "" : cursor)) {
        throw unavailable();
      }
      McpSchema.ListResourcesResult response =
          await(gateway.listResources(cursor).toFuture(), settings.requestTimeout());
      if (response.resources() == null) {
        throw unavailable();
      }
      for (McpSchema.Resource resource : response.resources()) {
        if (++discovered > 32) {
          throw unavailable();
        }
        projectResource(serverId, resource).ifPresent(assets::add);
      }
      cursor = nextCursor(response.nextCursor());
      if (cursor == null) {
        return;
      }
    }
  }

  private List<McpRemoteTool> discoverTools()
      throws InterruptedException, ExecutionException, TimeoutException {
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
  }

  private McpAssetCatalog discoverAssets(McpSchema.InitializeResult initialization, String serverId)
      throws InterruptedException, ExecutionException, TimeoutException {
    List<McpAssetDescriptor> assets = new ArrayList<>();
    try {
      if (initialization.capabilities().resources() != null) {
        discoverResources(serverId, assets);
      }
      if (initialization.capabilities().prompts() != null) {
        discoverPrompts(serverId, assets);
      }
      return new McpAssetCatalog(assets);
    } catch (ExecutionException | TimeoutException unavailable) {
      return McpAssetCatalog.empty();
    } catch (RuntimeException unavailable) {
      return McpAssetCatalog.empty();
    }
  }

  private void discoverPrompts(String serverId, List<McpAssetDescriptor> assets)
      throws InterruptedException, ExecutionException, TimeoutException {
    String cursor = null;
    Set<String> cursors = new HashSet<>();
    int discovered = 0;
    for (int page = 0; ; page++) {
      if (page >= settings.maxListPages() || !cursors.add(cursor == null ? "" : cursor)) {
        throw unavailable();
      }
      McpSchema.ListPromptsResult response =
          await(gateway.listPrompts(cursor).toFuture(), settings.requestTimeout());
      if (response.prompts() == null) {
        throw unavailable();
      }
      for (McpSchema.Prompt prompt : response.prompts()) {
        if (++discovered > 32) {
          throw unavailable();
        }
        projectPrompt(serverId, prompt).ifPresent(assets::add);
      }
      cursor = nextCursor(response.nextCursor());
      if (cursor == null) {
        return;
      }
    }
  }

  private static Optional<McpAssetDescriptor> projectResource(
      String serverId, McpSchema.Resource resource) {
    try {
      if (resource == null
          || resource.uri() == null
          || !java.net.URI.create(resource.uri()).isAbsolute()) {
        return Optional.empty();
      }
      return Optional.of(
          new McpAssetDescriptor(
              1,
              serverId,
              McpAssetKind.RESOURCE,
              "mcp_" + serverId + "__resource_" + sha256Prefix(resource.uri()),
              resource.name(),
              resource.description(),
              true));
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  private static Optional<McpAssetDescriptor> projectPrompt(
      String serverId, McpSchema.Prompt prompt) {
    try {
      if (prompt == null
          || prompt.arguments() == null
          || prompt.arguments().size() > 16
          || prompt.name() == null
          || !prompt.name().matches("[a-z][a-z0-9_]{0,31}")) {
        return Optional.empty();
      }
      for (McpSchema.PromptArgument argument : prompt.arguments()) {
        if (argument == null
            || argument.name() == null
            || !argument.name().matches("[a-z][a-z0-9_]{0,63}")) {
          return Optional.empty();
        }
      }
      return Optional.of(
          new McpAssetDescriptor(
              1,
              serverId,
              McpAssetKind.PROMPT,
              "mcp_" + serverId + "__prompt_" + prompt.name(),
              prompt.name(),
              prompt.description(),
              true));
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  private static String nextCursor(String cursor) {
    if (cursor == null) {
      return null;
    }
    if (cursor.isBlank() || cursor.codePointCount(0, cursor.length()) > 512) {
      throw unavailable();
    }
    return cursor;
  }

  private static String sha256Prefix(String value) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(16);
      for (int index = 0; index < 8; index++) {
        result.append(String.format("%02x", digest[index]));
      }
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
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
