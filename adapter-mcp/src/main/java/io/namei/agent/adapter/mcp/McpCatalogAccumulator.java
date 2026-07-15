package io.namei.agent.adapter.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class McpCatalogAccumulator {
  private static final int MAX_CURSOR_BYTES = 512;
  private static final int MAX_REMOTE_NAME_BYTES = 512;

  private final int maxPages;
  private final int maxTools;
  private final List<McpRemoteTool> tools = new ArrayList<>();
  private final Set<String> names = new HashSet<>();
  private final Set<String> emittedCursors = new HashSet<>();

  private int pageCount;
  private String expectedCursor;
  private boolean terminal;

  McpCatalogAccumulator(int maxPages, int maxTools) {
    if (maxPages < 1 || maxTools < 1) {
      throw new IllegalArgumentException("MCP Catalog 上限无效");
    }
    this.maxPages = maxPages;
    this.maxTools = maxTools;
  }

  void addPage(String requestCursor, String nextCursor, List<McpRemoteTool> page) {
    if (terminal
        || pageCount >= maxPages
        || !Objects.equals(requestCursor, expectedCursor)
        || page == null
        || tools.size() > maxTools - page.size()) {
      throw new McpCatalogException();
    }
    for (McpRemoteTool tool : page) {
      if (tool == null || !validRemoteName(tool.name()) || !names.add(tool.name())) {
        throw new McpCatalogException();
      }
    }
    tools.addAll(page);
    pageCount++;

    if (nextCursor == null) {
      terminal = true;
      expectedCursor = null;
      return;
    }
    if (!validCursor(nextCursor) || !emittedCursors.add(nextCursor)) {
      throw new McpCatalogException();
    }
    expectedCursor = nextCursor;
  }

  List<McpRemoteTool> finish() {
    if (!terminal || pageCount == 0) {
      throw new McpCatalogException();
    }
    return List.copyOf(tools);
  }

  private static boolean validCursor(String cursor) {
    return !cursor.isBlank()
        && cursor.getBytes(StandardCharsets.UTF_8).length <= MAX_CURSOR_BYTES
        && cursor.codePoints().noneMatch(Character::isISOControl);
  }

  private static boolean validRemoteName(String name) {
    return name != null
        && !name.isBlank()
        && name.getBytes(StandardCharsets.UTF_8).length <= MAX_REMOTE_NAME_BYTES
        && name.codePoints().noneMatch(Character::isISOControl);
  }
}
