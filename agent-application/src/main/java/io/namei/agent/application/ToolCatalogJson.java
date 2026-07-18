package io.namei.agent.application;

final class ToolCatalogJson {
  private ToolCatalogJson() {}

  static String render(ToolCatalogSearchResult result) {
    var output = new StringBuilder("{\"matched\":[");
    for (int index = 0; index < result.matched().size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      ToolCatalogMatch match = result.matched().get(index);
      output
          .append("{\"always_on\":")
          .append(match.alwaysOn())
          .append(",\"name\":")
          .append(string(match.name()))
          .append(",\"risk\":")
          .append(string(match.risk().name()))
          .append(",\"source\":")
          .append(string(match.source().name()))
          .append(",\"summary\":")
          .append(string(match.summary()))
          .append('}');
    }
    output.append("],\"unlocked\":");
    array(output, result.unlocked());
    output.append(",\"already_loaded\":");
    array(output, result.alreadyLoaded());
    return output.append('}').toString();
  }

  private static void array(StringBuilder output, java.util.List<String> values) {
    output.append('[');
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      output.append(string(values.get(index)));
    }
    output.append(']');
  }

  private static String string(String value) {
    var escaped = new StringBuilder("\"");
    for (int offset = 0; offset < value.length(); ) {
      char character = value.charAt(offset++);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }
}
