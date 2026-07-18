package io.namei.agent.bootstrap.control;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@FunctionalInterface
public interface ControlSseWriterFactory {
  ControlSseWriter open(HttpServletResponse response) throws IOException;
}
