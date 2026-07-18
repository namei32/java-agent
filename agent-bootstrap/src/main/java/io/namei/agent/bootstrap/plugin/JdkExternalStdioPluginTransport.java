package io.namei.agent.bootstrap.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** JDK 直连 stdio Transport：无 Shell、无环境变量继承、固定根目录，并在读取前限制单行大小。 */
public final class JdkExternalStdioPluginTransport implements ExternalStdioPluginTransport {
  private final Process process;
  private final InputStream stdout;
  private final OutputStream stdin;
  private final int maxFrameBytes;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread stderrDrainer;

  private JdkExternalStdioPluginTransport(Process process, int maxFrameBytes) {
    this.process = Objects.requireNonNull(process, "process");
    this.stdout = process.getInputStream();
    this.stdin = process.getOutputStream();
    this.maxFrameBytes = maxFrameBytes;
    this.stderrDrainer =
        Thread.ofVirtual()
            .name("namei-plugin-stderr-drain")
            .start(() -> drain(process.getErrorStream()));
  }

  public static JdkExternalStdioPluginTransport start(
      ExternalStdioCommand command, ExternalStdioBridgeLimits limits) throws IOException {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(limits, "limits");
    var builder = new ProcessBuilder(command.tokens());
    builder.directory(Path.of("/").toFile());
    builder.environment().clear();
    builder.redirectErrorStream(false);
    return new JdkExternalStdioPluginTransport(builder.start(), limits.maxFrameBytes());
  }

  @Override
  public synchronized String exchange(String request, Duration timeout)
      throws IOException, TimeoutException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(timeout, "timeout");
    if (closed.get()
        || !process.isAlive()
        || request.indexOf('\n') >= 0
        || request.indexOf('\r') >= 0) {
      throw new IOException("External stdio 不可用");
    }
    try {
      stdin.write(request.getBytes(StandardCharsets.UTF_8));
      stdin.write('\n');
      stdin.flush();
    } catch (IOException failure) {
      close(timeout);
      throw failure;
    }
    var reader = new FutureTask<>(this::readFrame);
    Thread.ofVirtual().name("namei-plugin-stdout-read").start(reader);
    try {
      return reader.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      reader.cancel(true);
      close(timeout);
      Thread.currentThread().interrupt();
      throw new IOException("External stdio 读取中断", interrupted);
    } catch (TimeoutException timeoutFailure) {
      reader.cancel(true);
      close(timeout);
      throw timeoutFailure;
    } catch (ExecutionException failedRead) {
      close(timeout);
      Throwable cause = failedRead.getCause();
      if (cause instanceof IOException failure) {
        throw failure;
      }
      throw new IOException("External stdio 读取失败", cause);
    }
  }

  @Override
  public void close(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    closeQuietly(stdin);
    closeQuietly(stdout);
    process.destroy();
    waitForProcess(deadline);
    if (process.isAlive()) {
      process.destroyForcibly();
      waitForProcess(deadline);
    }
    long remaining = deadline - System.nanoTime();
    if (remaining > 0) {
      try {
        stderrDrainer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private String readFrame() throws IOException {
    var output = new ByteArrayOutputStream(Math.min(maxFrameBytes, 8192));
    while (true) {
      int next = stdout.read();
      if (next < 0) {
        throw new IOException("External stdio 已退出");
      }
      if (next == '\n') {
        return output.toString(StandardCharsets.UTF_8);
      }
      if (output.size() >= maxFrameBytes) {
        throw new IOException("External stdio frame 超限");
      }
      output.write(next);
    }
  }

  private static void drain(InputStream input) {
    try (input) {
      input.transferTo(OutputStream.nullOutputStream());
    } catch (IOException ignored) {
      // 子进程退出和关闭时的 stderr 失败不应进入日志或主链路。
    }
  }

  private void waitForProcess(long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      return;
    }
    try {
      processWait(remaining);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void processWait(long remaining) throws InterruptedException {
    process.waitFor(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // Best effort during a bounded shutdown.
    }
  }
}
