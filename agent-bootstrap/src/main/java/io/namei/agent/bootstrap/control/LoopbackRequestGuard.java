package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlStableCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public final class LoopbackRequestGuard {
  private static final Pattern IPV4_HOST = Pattern.compile("127\\.0\\.0\\.1(?::[1-9][0-9]{0,4})?");
  private static final Pattern IPV6_HOST = Pattern.compile("\\[::1](?::[1-9][0-9]{0,4})?");
  private static final Pattern LOCALHOST = Pattern.compile("localhost(?::[1-9][0-9]{0,4})?");
  private static final Pattern TURN_REF = Pattern.compile("[A-Za-z0-9_-]{22}");
  private static final String PENDING_OPERATION_PREFIX = "/api/v1/control/pending-operations";
  private static final String READ_ONLY_INDEX_PATH = "/api/v1/control/index";
  private static final String READ_ONLY_HISTORY_PATH = "/api/v1/control/history";
  private static final String READ_ONLY_HISTORY_DETAIL_PATH = "/api/v1/control/history/detail";

  public void validate(HttpServletRequest request) {
    if (!approvedShape(request.getMethod(), request.getRequestURI()) || !allowsQuery(request)) {
      reject(requestInvalidCode(request.getRequestURI()), 400);
    }
    if (!allowsDecisionBody(request)) {
      validateEmptyBody(request, requestInvalidCode(request.getRequestURI()));
    }
    if (!isLoopback(request.getRemoteAddr())) {
      reject(ControlStableCode.CONTROL_REMOTE_ACCESS_REJECTED, 403);
    }
    String host = singleHeader(request, "Host", ControlStableCode.CONTROL_HOST_REJECTED);
    if (!validHost(host)) {
      reject(ControlStableCode.CONTROL_HOST_REJECTED, 403);
    }
    List<String> origins = headers(request, "Origin");
    if (origins.size() > 1
        || (origins.size() == 1
            && !(request.getScheme() + "://" + host).equals(origins.getFirst()))) {
      reject(ControlStableCode.CONTROL_ORIGIN_REJECTED, 403);
    }
  }

  private static boolean allowsQuery(HttpServletRequest request) {
    if (request.getQueryString() == null) {
      return true;
    }
    return "GET".equals(request.getMethod())
        && (READ_ONLY_INDEX_PATH.equals(request.getRequestURI())
            || READ_ONLY_HISTORY_PATH.equals(request.getRequestURI())
            || READ_ONLY_HISTORY_DETAIL_PATH.equals(request.getRequestURI()))
        && !request.getQueryString().isEmpty();
  }

  private static boolean approvedShape(String method, String path) {
    if ("POST".equals(method) || "DELETE".equals(method)) {
      if ("/api/v1/control/session".equals(path)) {
        return true;
      }
    }
    if ("GET".equals(method)) {
      if ("/api/v1/control/status".equals(path)
          || "/api/v1/control/turns".equals(path)
          || READ_ONLY_INDEX_PATH.equals(path)
          || READ_ONLY_HISTORY_PATH.equals(path)
          || READ_ONLY_HISTORY_DETAIL_PATH.equals(path)) {
        return true;
      }
      if ("/api/v1/control/approvals".equals(path)) {
        return true;
      }
      if (pendingOperationPath(path, "")) {
        return true;
      }
      return turnPath(path, "/events");
    }
    return ("POST".equals(method) && turnPath(path, "/cancel"))
        || approvalDecisionPath(method, path)
        || ("POST".equals(method)
            && (pendingOperationPath(path, "/resume") || pendingOperationPath(path, "/cancel")));
  }

  private static boolean allowsDecisionBody(HttpServletRequest request) {
    return approvalDecisionPath(request.getMethod(), request.getRequestURI());
  }

  private static boolean turnPath(String path, String suffix) {
    String prefix = "/api/v1/control/turns/";
    if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) {
      return false;
    }
    String reference = path.substring(prefix.length(), path.length() - suffix.length());
    return TURN_REF.matcher(reference).matches();
  }

  private static boolean approvalDecisionPath(String method, String path) {
    String prefix = "/api/v1/control/approvals/";
    String suffix = "/decisions";
    if (!"POST".equals(method)
        || path == null
        || !path.startsWith(prefix)
        || !path.endsWith(suffix)) {
      return false;
    }
    String reference = path.substring(prefix.length(), path.length() - suffix.length());
    return TURN_REF.matcher(reference).matches();
  }

  private static boolean pendingOperationPath(String path, String suffix) {
    String prefix = PENDING_OPERATION_PREFIX + "/";
    if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) {
      return false;
    }
    String reference = path.substring(prefix.length(), path.length() - suffix.length());
    return TURN_REF.matcher(reference).matches();
  }

  private static void validateEmptyBody(HttpServletRequest request, ControlStableCode code) {
    if (request.getContentLengthLong() > 0) {
      reject(code, 400);
    }
    if (request.getContentLengthLong() == 0 && request.getHeader("Transfer-Encoding") == null) {
      return;
    }
    try {
      if (request.getInputStream().read() != -1) {
        reject(code, 400);
      }
    } catch (IOException failure) {
      reject(code, 400);
    }
  }

  private static ControlStableCode requestInvalidCode(String path) {
    return path != null && path.startsWith(PENDING_OPERATION_PREFIX)
        ? ControlStableCode.PENDING_RECOVERY_REQUEST_INVALID
        : ControlStableCode.CONTROL_REQUEST_INVALID;
  }

  private static boolean isLoopback(String value) {
    if ("::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value)) {
      return true;
    }
    if (value == null || !value.startsWith("127.")) {
      return false;
    }
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    for (String part : parts) {
      try {
        if (part.isEmpty() || Integer.parseInt(part) > 255) {
          return false;
        }
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return true;
  }

  private static boolean validHost(String host) {
    if (host == null
        || !(IPV4_HOST.matcher(host).matches()
            || IPV6_HOST.matcher(host).matches()
            || LOCALHOST.matcher(host).matches())) {
      return false;
    }
    if ("[::1]".equals(host)) {
      return true;
    }
    int separator = host.lastIndexOf(':');
    if (separator < 0) {
      return true;
    }
    try {
      return Integer.parseInt(host.substring(separator + 1)) <= 65_535;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static String singleHeader(
      HttpServletRequest request, String name, ControlStableCode failure) {
    List<String> values = headers(request, name);
    if (values.size() != 1) {
      reject(failure, 403);
    }
    return values.getFirst();
  }

  private static List<String> headers(HttpServletRequest request, String name) {
    var enumeration = request.getHeaders(name);
    if (enumeration == null) {
      return List.of();
    }
    return new ArrayList<>(Collections.list(enumeration));
  }

  private static void reject(ControlStableCode code, int status) {
    throw new ControlRequestRejectedException(code, status);
  }
}
