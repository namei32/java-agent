package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlSequencedEvent;
import io.namei.agent.application.control.ControlStreamOpening;
import java.io.IOException;

public interface ControlSseWriter {
  void opened(ControlStreamOpening opening) throws IOException;

  void message(ControlSequencedEvent event) throws IOException;

  void keepalive() throws IOException;
}
