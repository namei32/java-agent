package io.namei.agent.bootstrap.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ContentLengthLimitFilter extends OncePerRequestFilter {
  static final long MAX_BYTES = 65_536;
  private static final String TOO_LARGE_PROBLEM =
      """
      {"type":"about:blank","title":"请求体过大","status":413,"detail":"请求体过大"}
      """
          .strip();

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/api/v1/control");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BYTES) {
      reject(response);
      return;
    }

    byte[] body = request.getInputStream().readNBytes((int) MAX_BYTES + 1);
    if (body.length > MAX_BYTES) {
      reject(response);
      return;
    }
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(TOO_LARGE_PROBLEM);
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body.clone();
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
      Charset charset =
          getCharacterEncoding() == null
              ? StandardCharsets.UTF_8
              : Charset.forName(getCharacterEncoding());
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class ByteArrayServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream input;

    private ByteArrayServletInputStream(byte[] body) {
      this.input = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return input.read();
    }

    @Override
    public boolean isFinished() {
      return input.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
      Objects.requireNonNull(listener, "listener");
      try {
        if (!isFinished()) {
          listener.onDataAvailable();
        }
        if (isFinished()) {
          listener.onAllDataRead();
        }
      } catch (IOException exception) {
        listener.onError(exception);
      }
    }
  }
}
