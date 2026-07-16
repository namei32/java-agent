package io.namei.agent.adapter.springai;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * Supplies the cancellation propagation currently missing between Spring AI's Reactor stream and
 * the OpenAI Java SDK asynchronous response.
 */
final class OpenAiStreamCancellationRegistry implements OpenAiHttpClientBuilderCustomizer {
  static final String CORRELATION_HEADER = "X-Namei-Internal-Stream-Id";
  private static final Duration STALE_ENTRY_AGE = Duration.ofMinutes(1);
  private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

  @Override
  public void customize(SpringAiOpenAiHttpClient.Builder builder) {
    builder.interceptor(this::intercept);
  }

  Registration open(ChatModel model) {
    Objects.requireNonNull(model, "model");
    if (!(model instanceof OpenAiChatModel)) {
      return Registration.NONE;
    }
    pruneStaleEntries();
    String id = UUID.randomUUID().toString();
    var entry = new Entry(id, System.nanoTime());
    entries.put(id, entry);
    return new Registration(this, entry);
  }

  private Response intercept(Interceptor.Chain chain) throws IOException {
    String id = chain.request().header(CORRELATION_HEADER);
    if (id == null) {
      return chain.proceed(chain.request());
    }

    Request providerRequest = chain.request().newBuilder().removeHeader(CORRELATION_HEADER).build();
    Entry entry = entries.get(id);
    if (entry == null) {
      return chain.proceed(providerRequest);
    }
    if (!entry.attach(chain.call())) {
      entries.remove(id, entry);
      throw new IOException("OpenAI stream cancelled before transport started");
    }

    try {
      Response response = chain.proceed(providerRequest);
      ResponseBody body = response.body();
      if (body == null) {
        complete(entry);
        return response;
      }
      return response
          .newBuilder()
          .body(new TrackingResponseBody(body, () -> complete(entry)))
          .build();
    } catch (IOException | RuntimeException failure) {
      complete(entry);
      throw failure;
    }
  }

  private void cancel(Entry entry) {
    entry.cancel();
    if (entry.hasTransport()) {
      entries.remove(entry.id(), entry);
    }
  }

  private void close(Entry entry) {
    if (!entry.isCancelled() || entry.hasTransport()) {
      entries.remove(entry.id(), entry);
    }
  }

  private void complete(Entry entry) {
    entry.complete();
    entries.remove(entry.id(), entry);
  }

  private void pruneStaleEntries() {
    long staleBefore = System.nanoTime() - STALE_ENTRY_AGE.toNanos();
    entries.forEach(
        (id, entry) -> {
          if (entry.createdAtNanos() < staleBefore && entries.remove(id, entry)) {
            entry.cancel();
          }
        });
  }

  static final class Registration implements AutoCloseable {
    static final Registration NONE = new Registration();
    private final OpenAiStreamCancellationRegistry owner;
    private final Entry entry;

    private Registration() {
      owner = null;
      entry = null;
    }

    private Registration(OpenAiStreamCancellationRegistry owner, Entry entry) {
      this.owner = owner;
      this.entry = entry;
    }

    Map<String, String> requestHeaders() {
      return entry == null ? Map.of() : Map.of(CORRELATION_HEADER, entry.id());
    }

    void cancel() {
      if (entry != null) {
        owner.cancel(entry);
      }
    }

    @Override
    public void close() {
      if (entry != null) {
        owner.close(entry);
      }
    }
  }

  private static final class Entry {
    private final String id;
    private final long createdAtNanos;
    private final AtomicReference<Call> transport = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private Entry(String id, long createdAtNanos) {
      this.id = id;
      this.createdAtNanos = createdAtNanos;
    }

    private boolean attach(Call call) {
      if (!transport.compareAndSet(null, Objects.requireNonNull(call, "call"))) {
        throw new IllegalStateException("OpenAI stream transport already attached");
      }
      if (cancelled.get()) {
        call.cancel();
        return false;
      }
      return true;
    }

    private void cancel() {
      cancelled.set(true);
      Call call = transport.get();
      if (call != null) {
        call.cancel();
      }
    }

    private void complete() {
      transport.set(null);
    }

    private String id() {
      return id;
    }

    private long createdAtNanos() {
      return createdAtNanos;
    }

    private boolean hasTransport() {
      return transport.get() != null;
    }

    private boolean isCancelled() {
      return cancelled.get();
    }
  }

  private static final class TrackingResponseBody extends ResponseBody {
    private final ResponseBody delegate;
    private final Runnable completion;
    private final AtomicBoolean completed = new AtomicBoolean();
    private final BufferedSource source;

    private TrackingResponseBody(ResponseBody delegate, Runnable completion) {
      this.delegate = delegate;
      this.completion = completion;
      this.source =
          Okio.buffer(
              new ForwardingSource(delegate.source()) {
                @Override
                public long read(okio.Buffer sink, long byteCount) throws IOException {
                  try {
                    long read = super.read(sink, byteCount);
                    if (read == -1) {
                      finish();
                    }
                    return read;
                  } catch (IOException failure) {
                    finish();
                    throw failure;
                  }
                }

                @Override
                public void close() throws IOException {
                  try {
                    super.close();
                  } finally {
                    finish();
                  }
                }
              });
    }

    @Override
    public MediaType contentType() {
      return delegate.contentType();
    }

    @Override
    public long contentLength() {
      return delegate.contentLength();
    }

    @Override
    public BufferedSource source() {
      return source;
    }

    private void finish() {
      if (completed.compareAndSet(false, true)) {
        completion.run();
      }
    }
  }
}
