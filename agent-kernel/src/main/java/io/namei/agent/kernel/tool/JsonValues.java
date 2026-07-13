package io.namei.agent.kernel.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class JsonValues {
  private JsonValues() {}

  static Map<String, Object> immutableObject(Map<String, ?> value) {
    Objects.requireNonNull(value, "value");
    var copy = new LinkedHashMap<String, Object>();
    value.forEach(
        (key, item) -> copy.put(Objects.requireNonNull(key, "JSON Object key"), immutable(item)));
    return Collections.unmodifiableMap(copy);
  }

  private static Object immutable(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      var copy = new LinkedHashMap<String, Object>();
      map.forEach(
          (key, item) -> {
            if (!(key instanceof String text)) {
              throw new IllegalArgumentException("JSON Object key 必须是字符串");
            }
            copy.put(text, immutable(item));
          });
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      var copy = new ArrayList<>();
      list.forEach(item -> copy.add(immutable(item)));
      return Collections.unmodifiableList(copy);
    }
    throw new IllegalArgumentException("只允许 JSON 兼容值");
  }
}
