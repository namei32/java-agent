package io.namei.agent.adapter.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

final class McpSchemaProjector {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> KEYWORDS =
      Set.of("type", "properties", "required", "additionalProperties", "enum");
  private static final Set<String> TYPES =
      Set.of("string", "integer", "number", "boolean", "object", "array");
  private static final int MAX_DEPTH = 16;
  private static final int MAX_PROPERTIES = 128;
  private static final int MAX_ENUM_VALUES = 128;
  private static final int MAX_PROPERTY_NAME_BYTES = 256;

  private final int maxSchemaBytes;

  McpSchemaProjector(int maxSchemaBytes) {
    if (maxSchemaBytes < 1 || maxSchemaBytes > 1_048_576) {
      throw new IllegalArgumentException("MCP Schema 上限无效");
    }
    this.maxSchemaBytes = maxSchemaBytes;
  }

  Optional<Map<String, Object>> project(Map<String, Object> inputSchema) {
    try {
      Map<String, Object> raw = inputSchema == null ? Map.of() : inputSchema;
      PropertyCounter counter = new PropertyCounter();
      Map<String, Object> normalized = normalize(raw, true, 0, counter);
      if (JSON.writeValueAsBytes(normalized).length > maxSchemaBytes) {
        return Optional.empty();
      }
      return Optional.of(normalized);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private static Map<String, Object> normalize(
      Map<?, ?> raw, boolean root, int depth, PropertyCounter counter) {
    if (depth > MAX_DEPTH) {
      throw invalid();
    }
    for (Object key : raw.keySet()) {
      if (!(key instanceof String keyword) || !KEYWORDS.contains(keyword)) {
        throw invalid();
      }
    }

    Object rawType = raw.get("type");
    String type;
    if (root && raw.isEmpty()) {
      type = "object";
    } else if (rawType instanceof String candidate && TYPES.contains(candidate)) {
      type = candidate;
    } else {
      throw invalid();
    }
    if (root && !"object".equals(type)) {
      throw invalid();
    }

    LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
    normalized.put("type", type);
    if (raw.containsKey("enum")) {
      normalized.put("enum", normalizeEnum(type, raw.get("enum")));
    }
    if (!"object".equals(type)) {
      if (raw.containsKey("properties")
          || raw.containsKey("required")
          || raw.containsKey("additionalProperties")) {
        throw invalid();
      }
      return Map.copyOf(normalized);
    }

    Map<?, ?> rawProperties = mapOrEmpty(raw.get("properties"));
    counter.add(rawProperties.size());
    LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawProperties.entrySet()) {
      if (!(entry.getKey() instanceof String propertyName)
          || propertyName.isBlank()
          || containsControl(propertyName)
          || propertyName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              > MAX_PROPERTY_NAME_BYTES
          || !(entry.getValue() instanceof Map<?, ?> propertySchema)) {
        throw invalid();
      }
      properties.put(propertyName, normalize(propertySchema, false, depth + 1, counter));
    }
    normalized.put("properties", Map.copyOf(properties));

    if (raw.containsKey("required")) {
      if (!(raw.get("required") instanceof List<?> rawRequired)
          || rawRequired.size() > MAX_PROPERTIES) {
        throw invalid();
      }
      List<String> required = new ArrayList<>(rawRequired.size());
      Set<String> unique = new HashSet<>();
      for (Object item : rawRequired) {
        if (!(item instanceof String name) || !properties.containsKey(name) || !unique.add(name)) {
          throw invalid();
        }
        required.add(name);
      }
      normalized.put("required", List.copyOf(required));
    }

    if (raw.containsKey("additionalProperties")
        && !Boolean.FALSE.equals(raw.get("additionalProperties"))) {
      throw invalid();
    }
    normalized.put("additionalProperties", false);
    return Map.copyOf(normalized);
  }

  private static List<Object> normalizeEnum(String type, Object rawEnum) {
    if (!(rawEnum instanceof List<?> values)
        || values.isEmpty()
        || values.size() > MAX_ENUM_VALUES) {
      throw invalid();
    }
    List<Object> result = new ArrayList<>(values.size());
    for (Object value : values) {
      if (!matchesType(type, value)) {
        throw invalid();
      }
      result.add(value);
    }
    return List.copyOf(result);
  }

  private static boolean matchesType(String type, Object value) {
    return switch (type) {
      case "string" -> value instanceof String;
      case "integer" -> isInteger(value);
      case "number" -> value instanceof Number && isFinite((Number) value);
      case "boolean" -> value instanceof Boolean;
      default -> false;
    };
  }

  private static boolean isInteger(Object value) {
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger) {
      return true;
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros().scale() <= 0;
    }
    if (value instanceof Float number) {
      return Float.isFinite(number) && Math.rint(number) == number;
    }
    if (value instanceof Double number) {
      return Double.isFinite(number) && Math.rint(number) == number;
    }
    return false;
  }

  private static boolean isFinite(Number value) {
    if (value instanceof Double number) {
      return Double.isFinite(number);
    }
    if (value instanceof Float number) {
      return Float.isFinite(number);
    }
    return true;
  }

  private static Map<?, ?> mapOrEmpty(Object value) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw invalid();
    }
    return map;
  }

  private static boolean containsControl(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("不支持的 MCP Schema");
  }

  private static final class PropertyCounter {
    private int count;

    void add(int amount) {
      if (amount < 0 || count > MAX_PROPERTIES - amount) {
        throw invalid();
      }
      count += amount;
    }
  }
}
