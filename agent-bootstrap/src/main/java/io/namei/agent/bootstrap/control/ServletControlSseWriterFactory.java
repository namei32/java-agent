package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlSequencedEvent;
import io.namei.agent.application.control.ControlStreamOpening;
import io.namei.agent.kernel.control.ControlEventProjection;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class ServletControlSseWriterFactory implements ControlSseWriterFactory {
  private final ObjectMapper json;

  public ServletControlSseWriterFactory(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public ControlSseWriter open(HttpServletResponse response) throws IOException {
    Objects.requireNonNull(response, "response");
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/event-stream");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    return new ServletControlSseWriter(response.getOutputStream(), json);
  }

  private static final class ServletControlSseWriter implements ControlSseWriter {
    private final ServletOutputStream output;
    private final ObjectMapper json;

    private ServletControlSseWriter(ServletOutputStream output, ObjectMapper json) {
      this.output = output;
      this.json = json;
    }

    @Override
    public void opened(ControlStreamOpening opening) throws IOException {
      write(
          0,
          "control.stream.opened.v1",
          new OpeningPayload(
              1,
              opening.turnRef().value(),
              opening.state().name(),
              opening.lastSequence(),
              opening.replaySupported(),
              opening.subscribedAt()));
    }

    @Override
    public void message(ControlSequencedEvent event) throws IOException {
      ControlEventProjection projection = event.projection();
      write(
          event.deliverySequence(),
          "control.turn.message.v1",
          new MessagePayload(
              projection.schemaVersion(),
              projection.turnRef().value(),
              projection.sequence(),
              projection.type().name(),
              projection.content(),
              projection.code(),
              projection.retryable()));
    }

    @Override
    public void keepalive() throws IOException {
      output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
      output.flush();
    }

    private void write(long id, String event, Object payload) throws IOException {
      output.write(
          ("id: " + id + "\nevent: " + event + "\ndata: ").getBytes(StandardCharsets.UTF_8));
      output.write(json.writeValueAsBytes(payload));
      output.write('\n');
      output.write('\n');
      output.flush();
    }
  }

  private record OpeningPayload(
      int schemaVersion,
      String turnRef,
      String state,
      Long lastSequence,
      boolean replaySupported,
      Instant subscribedAt) {}

  private record MessagePayload(
      int schemaVersion,
      String turnRef,
      long sequence,
      String type,
      String content,
      String code,
      boolean retryable) {}
}
