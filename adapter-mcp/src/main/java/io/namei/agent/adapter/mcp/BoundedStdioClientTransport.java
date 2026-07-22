package io.namei.agent.adapter.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** 在反序列化前限制 Wire 大小并由适配器自持取消能力的 stdio Transport。 */
final class BoundedStdioClientTransport implements McpClientTransport {
  private static final int MAX_PENDING_REQUESTS = 256;
  private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {};
  private static final Consumer<JSONRPCMessage> NOOP_OBSERVER = ignored -> {};

  private final McpServerDefinition server;
  private final Duration connectTimeout;
  private final Duration shutdownTimeout;
  private final int maxWireBytes;
  private final McpJsonMapper jsonMapper;
  private final Consumer<JSONRPCMessage> observer;
  private final Object lifecycleLock = new Object();
  private final Object writeLock = new Object();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final AtomicReference<McpClientException> failure = new AtomicReference<>();
  private final CountDownLatch connected = new CountDownLatch(1);
  private final CountDownLatch closed = new CountDownLatch(1);
  private final ThreadLocal<McpCallHandle> currentCall = new ThreadLocal<>();
  private final ConcurrentHashMap<McpCallHandle, Boolean> calls = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Object, Boolean> pendingRequestIds = new ConcurrentHashMap<>();

  private volatile Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundHandler;
  private volatile Consumer<Throwable> exceptionHandler = ignored -> {};
  private volatile Process process;
  private volatile OutputStream stdin;
  private volatile Thread stdoutThread;
  private volatile Thread stderrThread;

  BoundedStdioClientTransport(McpServerDefinition server, McpSettings settings) {
    this(server, settings, NOOP_OBSERVER);
  }

  BoundedStdioClientTransport(
      McpServerDefinition server, McpSettings settings, Consumer<JSONRPCMessage> observer) {
    this.server = Objects.requireNonNull(server, "server");
    Objects.requireNonNull(settings, "settings");
    this.connectTimeout = settings.connectTimeout();
    this.shutdownTimeout = settings.shutdownTimeout();
    this.maxWireBytes = settings.maxWireBytes();
    this.observer = Objects.requireNonNull(observer, "observer");
    this.jsonMapper = McpJsonDefaults.getMapper();
  }

  @Override
  public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> messageHandler) {
    Objects.requireNonNull(messageHandler, "messageHandler");
    return Mono.fromRunnable(
        () -> {
          synchronized (lifecycleLock) {
            if (closing.get()) {
              throw unavailable();
            }
            if (started.compareAndSet(false, true)) {
              inboundHandler = messageHandler;
              startProcess();
            }
          }
        });
  }

  @Override
  public void setExceptionHandler(Consumer<Throwable> handler) {
    exceptionHandler = Objects.requireNonNull(handler, "handler");
  }

  @Override
  public Mono<Void> sendMessage(JSONRPCMessage message) {
    Objects.requireNonNull(message, "message");
    return Mono.fromRunnable(() -> writeMessage(message));
  }

  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    return jsonMapper.convertValue(data, typeRef);
  }

  @Override
  public List<String> protocolVersions() {
    return List.of(ProtocolVersions.MCP_2025_11_25);
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(this::closeInternal);
  }

  @Override
  public void close() {
    closeInternal();
  }

  McpCallHandle newCallHandle() {
    McpCallHandle handle = new McpCallHandle(this);
    calls.put(handle, Boolean.TRUE);
    return handle;
  }

  <T> T withCallHandle(McpCallHandle handle, Supplier<T> action) {
    Objects.requireNonNull(handle, "handle");
    Objects.requireNonNull(action, "action");
    if (currentCall.get() != null) {
      throw new IllegalStateException("MCP 调用上下文重复");
    }
    currentCall.set(handle);
    try {
      return action.get();
    } finally {
      currentCall.remove();
    }
  }

  void retire(McpCallHandle handle) {
    calls.remove(handle);
  }

  void cancelActiveCalls() {
    calls.keySet().forEach(McpCallHandle::cancel);
  }

  ProcessHandle processHandle() {
    Process current = process;
    if (current == null) {
      throw unavailable();
    }
    return current.toHandle();
  }

  boolean failed() {
    return failure.get() != null;
  }

  void trySendCancellation(Object requestId) {
    try {
      var notification =
          new McpSchema.JSONRPCNotification(
              "notifications/cancelled",
              Map.of("requestId", requestId, "reason", "client request cancelled"));
      synchronized (writeLock) {
        if (!closing.get() && failure.get() == null) {
          writeEncoded(notification);
        }
      }
    } catch (RuntimeException ignored) {
      // 对端或 Transport 已失败时，取消只能尽力执行。
    }
  }

  private void startProcess() {
    try {
      List<String> command = new ArrayList<>(server.arguments().size() + 1);
      command.add(server.executable().toString());
      command.addAll(server.arguments());
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.directory(server.workingDirectory().toFile());
      McpProcessEnvironment.replace(builder, server.environmentVariables(), System.getenv());
      Process child = builder.start();
      process = child;
      stdin = child.getOutputStream();
      stderrThread =
          Thread.ofVirtual()
              .name("namei-mcp-stderr-drain")
              .start(() -> drainStderr(child.getErrorStream()));
      stdoutThread =
          Thread.ofVirtual()
              .name("namei-mcp-stdout")
              .start(() -> readStdout(child.getInputStream()));
      connected.countDown();
    } catch (IOException | RuntimeException exception) {
      connected.countDown();
      fail();
      throw unavailable();
    }
  }

  private void writeMessage(JSONRPCMessage message) {
    awaitConnection();
    McpCallHandle handle = null;
    Object requestId = null;
    if (message instanceof McpSchema.JSONRPCRequest request) {
      requestId = request.id();
      if (pendingRequestIds.size() >= MAX_PENDING_REQUESTS
          || pendingRequestIds.putIfAbsent(requestId, Boolean.TRUE) != null) {
        throw unavailable();
      }
      if (McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
        handle = currentCall.get();
        if (handle == null) {
          pendingRequestIds.remove(requestId);
          throw unavailable();
        }
        handle.bind(request.id());
      }
    }
    try {
      synchronized (writeLock) {
        ensureAvailable();
        writeEncoded(message);
        if (handle != null) {
          handle.markWritten();
        }
      }
    } catch (RuntimeException exception) {
      if (requestId != null) {
        pendingRequestIds.remove(requestId);
      }
      throw exception;
    }
  }

  private void writeEncoded(JSONRPCMessage message) {
    try {
      byte[] encoded = jsonMapper.writeValueAsBytes(message);
      if (encoded.length > maxWireBytes) {
        throw unavailable();
      }
      OutputStream output = stdin;
      if (output == null) {
        throw unavailable();
      }
      output.write(encoded);
      output.write('\n');
      output.flush();
    } catch (IOException exception) {
      fail();
      throw unavailable();
    }
  }

  private void awaitConnection() {
    try {
      if (!connected.await(connectTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
        throw unavailable();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
    ensureAvailable();
  }

  private void ensureAvailable() {
    Process current = process;
    if (closing.get() || failure.get() != null || current == null || !current.isAlive()) {
      throw unavailable();
    }
  }

  private void readStdout(InputStream input) {
    try (input) {
      while (!closing.get()) {
        byte[] line = readBoundedLine(input);
        if (line == null) {
          if (!closing.get()) {
            fail();
          }
          return;
        }
        JSONRPCMessage message = decodeMessage(line);
        if (message instanceof McpSchema.JSONRPCResponse response
            && pendingRequestIds.remove(response.id()) == null) {
          throw unavailable();
        }
        observer.accept(message);
        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = inboundHandler;
        if (handler == null) {
          throw unavailable();
        }
        handler.apply(Mono.just(message)).block();
      }
    } catch (RuntimeException | IOException exception) {
      if (!closing.get()) {
        fail();
      }
    }
  }

  private void drainStderr(InputStream errorStream) {
    try (errorStream) {
      errorStream.transferTo(OutputStream.nullOutputStream());
    } catch (IOException ignored) {
      // 终止进程期间，流按预期会失败或关闭。
    }
  }

  private byte[] readBoundedLine(InputStream input) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxWireBytes, 8_192));
    while (true) {
      int next = input.read();
      if (next == -1) {
        if (line.size() == 0) {
          return null;
        }
        throw unavailable();
      }
      if (next == '\n') {
        byte[] value = line.toByteArray();
        if (value.length > 0 && value[value.length - 1] == '\r') {
          return java.util.Arrays.copyOf(value, value.length - 1);
        }
        return value;
      }
      if (line.size() >= maxWireBytes) {
        throw unavailable();
      }
      line.write(next);
    }
  }

  private JSONRPCMessage decodeMessage(byte[] line) {
    try {
      String json = decodeUtf8(line);
      Map<String, Object> fields = jsonMapper.readValue(json, MAP_TYPE);
      if (!McpSchema.JSONRPC_VERSION.equals(fields.get("jsonrpc"))) {
        throw unavailable();
      }
      if (fields.containsKey("method") && fields.containsKey("id")) {
        return jsonMapper.convertValue(fields, McpSchema.JSONRPCRequest.class);
      }
      if (fields.containsKey("method")) {
        return jsonMapper.convertValue(fields, McpSchema.JSONRPCNotification.class);
      }
      if (fields.containsKey("result") || fields.containsKey("error")) {
        return jsonMapper.convertValue(fields, McpSchema.JSONRPCResponse.class);
      }
      throw unavailable();
    } catch (IOException | RuntimeException exception) {
      throw unavailable();
    }
  }

  private static String decodeUtf8(byte[] value) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString();
  }

  private void fail() {
    McpClientException safeFailure = unavailable();
    if (failure.compareAndSet(null, safeFailure)) {
      connected.countDown();
      try {
        exceptionHandler.accept(safeFailure);
      } catch (RuntimeException ignored) {
        // 上层失败 Hook 不能阻止进程清理。
      }
      Process current = process;
      if (current != null && current.isAlive()) {
        current.destroyForcibly();
      }
    }
  }

  private void closeInternal() {
    if (!closing.compareAndSet(false, true)) {
      awaitClosed();
      return;
    }
    try {
      calls.keySet().forEach(McpCallHandle::cancel);
      pendingRequestIds.clear();
      closeQuietly(stdin);
      Process current = process;
      if (current != null && current.isAlive() && !waitFor(current, shutdownTimeout)) {
        current.destroy();
        if (!waitFor(current, shutdownTimeout)) {
          current.destroyForcibly();
          waitFor(current, shutdownTimeout);
        }
      }
      if (current != null) {
        closeQuietly(current.getInputStream());
        closeQuietly(current.getErrorStream());
      }
      join(stdoutThread);
      join(stderrThread);
    } finally {
      closed.countDown();
    }
  }

  private void awaitClosed() {
    try {
      closed.await(safeTimeoutMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean waitFor(Process child, Duration timeout) {
    try {
      return child.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      child.destroyForcibly();
      return false;
    }
  }

  private void join(Thread thread) {
    if (thread == null || thread == Thread.currentThread()) {
      return;
    }
    try {
      thread.join(safeTimeoutMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private long safeTimeoutMillis() {
    long millis = shutdownTimeout.toMillis();
    return Math.max(1, millis);
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (IOException ignored) {
      // 资源已经不可用。
    }
  }

  private static McpClientException unavailable() {
    return new McpClientException();
  }
}
