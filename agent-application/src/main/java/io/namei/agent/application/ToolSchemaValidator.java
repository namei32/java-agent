package io.namei.agent.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ToolSchemaValidator {
  private static final Set<String> SUPPORTED_KEYWORDS =
      Set.of("type", "properties", "required", "additionalProperties", "enum");
  private static final Set<String> SUPPORTED_TYPES =
      Set.of("string", "integer", "number", "boolean", "object", "array");

  private final Map<String, Object> schema;

  ToolSchemaValidator(Map<String, Object> schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
    validateSchema(schema, true);
  }

  boolean accepts(Map<String, Object> arguments) {
    return acceptsValue(schema, arguments);
  }

  private static void validateSchema(Map<String, Object> schema, boolean root) {
    for (String keyword : schema.keySet()) {
      if (!SUPPORTED_KEYWORDS.contains(keyword)) {
        throw invalidSchema("不支持关键字: " + keyword);
      }
    }

    Object typeValue = schema.get("type");
    if (!(typeValue instanceof String type) || !SUPPORTED_TYPES.contains(type)) {
      throw invalidSchema("type 无效");
    }
    if (root && !"object".equals(type)) {
      throw invalidSchema("顶层 type 必须是 object");
    }

    validateEnum(schema.get("enum"));
    if ("object".equals(type)) {
      validateObjectSchema(schema);
    } else if (schema.containsKey("properties")
        || schema.containsKey("required")
        || schema.containsKey("additionalProperties")) {
      throw invalidSchema("非 object 类型不能声明对象关键字");
    }
  }

  private static void validateEnum(Object enumValue) {
    if (enumValue == null) {
      return;
    }
    if (!(enumValue instanceof List<?> values) || values.isEmpty()) {
      throw invalidSchema("enum 必须是非空数组");
    }
  }

  private static void validateObjectSchema(Map<String, Object> schema) {
    Map<String, Object> properties = objectValue(schema.get("properties"), "properties");
    for (Map.Entry<String, Object> property : properties.entrySet()) {
      if (!(property.getValue() instanceof Map<?, ?> propertySchema)) {
        throw invalidSchema("property 必须是对象: " + property.getKey());
      }
      validateSchema(stringObjectMap(propertySchema, "property"), false);
    }

    Object requiredValue = schema.get("required");
    if (requiredValue != null) {
      if (!(requiredValue instanceof List<?> required)) {
        throw invalidSchema("required 必须是数组");
      }
      var names = new HashSet<String>();
      for (Object value : required) {
        if (!(value instanceof String name) || !properties.containsKey(name) || !names.add(name)) {
          throw invalidSchema("required 包含无效属性");
        }
      }
    }

    if (schema.containsKey("additionalProperties")
        && !Boolean.FALSE.equals(schema.get("additionalProperties"))) {
      throw invalidSchema("additionalProperties 只支持 false");
    }
  }

  private static boolean acceptsValue(Map<String, Object> schema, Object value) {
    if (!acceptsType((String) schema.get("type"), value)) {
      return false;
    }
    Object enumValue = schema.get("enum");
    if (enumValue instanceof List<?> values
        && values.stream().noneMatch(item -> item.equals(value))) {
      return false;
    }
    if (!"object".equals(schema.get("type"))) {
      return true;
    }

    if (!(value instanceof Map<?, ?> object)) {
      return false;
    }
    Map<String, Object> properties = objectValue(schema.get("properties"), "properties");
    Object requiredValue = schema.get("required");
    if (requiredValue instanceof List<?> required
        && required.stream().anyMatch(name -> !object.containsKey(name))) {
      return false;
    }
    if (Boolean.FALSE.equals(schema.get("additionalProperties"))
        && object.keySet().stream().anyMatch(key -> !properties.containsKey(key))) {
      return false;
    }
    for (Map.Entry<String, Object> property : properties.entrySet()) {
      if (object.containsKey(property.getKey())
          && !acceptsValue(
              stringObjectMap((Map<?, ?>) property.getValue(), "property"),
              object.get(property.getKey()))) {
        return false;
      }
    }
    return true;
  }

  private static boolean acceptsType(String type, Object value) {
    return switch (type) {
      case "string" -> value instanceof String;
      case "integer" -> isInteger(value);
      case "number" -> value instanceof Number;
      case "boolean" -> value instanceof Boolean;
      case "object" -> value instanceof Map<?, ?>;
      case "array" -> value instanceof List<?>;
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

  private static Map<String, Object> objectValue(Object value, String field) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw invalidSchema(field + " 必须是对象");
    }
    return stringObjectMap(map, field);
  }

  private static Map<String, Object> stringObjectMap(Map<?, ?> map, String field) {
    var result = new java.util.LinkedHashMap<String, Object>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalidSchema(field + " 的键必须是字符串");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static IllegalArgumentException invalidSchema(String reason) {
    return new IllegalArgumentException("工具 Schema 无效: " + reason);
  }
}
