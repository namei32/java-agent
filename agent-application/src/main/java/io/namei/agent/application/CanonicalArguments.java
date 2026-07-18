package io.namei.agent.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CanonicalArguments {
  private static final Comparator<String> CODE_POINT_ORDER = CanonicalArguments::compareCodePoints;

  private CanonicalArguments() {}

  static byte[] encode(Map<String, ?> arguments) {
    Objects.requireNonNull(arguments, "arguments");
    return encodeValue(arguments);
  }

  static byte[] encodeJson(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return encodeValue(parseJsonObject(json));
    } catch (IllegalArgumentException exception) {
      throw invalidJson();
    }
  }

  static Map<String, Object> parseJsonObject(String json) {
    Objects.requireNonNull(json, "json");
    Object decoded = new Parser(json).parse();
    if (!(decoded instanceof Map<?, ?> map)) {
      throw invalidJson();
    }
    var result = new java.util.LinkedHashMap<String, Object>();
    map.forEach(
        (key, value) -> {
          if (!(key instanceof String text)) {
            throw invalidJson();
          }
          result.put(text, value);
        });
    // Map.copyOf rejects null values, while JSON object values legitimately include null.
    // Keep the parsed shape immutable without narrowing the JSON value domain.
    return Collections.unmodifiableMap(result);
  }

  private static byte[] encodeValue(Object value) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        write(value, output);
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("无法生成审批参数编码", exception);
    }
  }

  private static void write(Object value, DataOutputStream output) throws IOException {
    if (value == null) {
      output.writeByte('n');
      return;
    }
    if (value instanceof Boolean bool) {
      output.writeByte('b');
      output.writeBoolean(bool);
      return;
    }
    if (value instanceof String text) {
      output.writeByte('s');
      writeText(text, output);
      return;
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger) {
      output.writeByte('i');
      writeText(value.toString(), output);
      return;
    }
    if (value instanceof BigDecimal decimal) {
      output.writeByte('d');
      writeText(decimal.toString(), output);
      return;
    }
    if (value instanceof Float floating) {
      writeFloating(floating, Float.toString(floating), output);
      return;
    }
    if (value instanceof Double floating) {
      writeFloating(floating, Double.toString(floating), output);
      return;
    }
    if (value instanceof Map<?, ?> map) {
      writeObject(map, output);
      return;
    }
    if (value instanceof List<?> list) {
      output.writeByte('a');
      output.writeInt(list.size());
      for (Object item : list) {
        write(item, output);
      }
      return;
    }
    throw new IllegalArgumentException("审批参数包含不稳定值");
  }

  private static void writeFloating(double value, String canonical, DataOutputStream output)
      throws IOException {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("审批参数包含非有限数字");
    }
    output.writeByte('d');
    writeText(canonical, output);
  }

  private static void writeObject(Map<?, ?> map, DataOutputStream output) throws IOException {
    var entries = new ArrayList<Map.Entry<String, Object>>(map.size());
    for (var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("审批参数 Object Key 必须是字符串");
      }
      entries.add(new AbstractMap.SimpleImmutableEntry<>(key, entry.getValue()));
    }
    entries.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
    output.writeByte('o');
    output.writeInt(entries.size());
    for (var entry : entries) {
      writeText(entry.getKey(), output);
      write(entry.getValue(), output);
    }
  }

  private static void writeText(String value, DataOutputStream output) throws IOException {
    byte[] encoded = utf8(value);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static byte[] utf8(String value) {
    try {
      var encoder = StandardCharsets.UTF_8.newEncoder();
      encoder.onMalformedInput(CodingErrorAction.REPORT);
      encoder.onUnmappableCharacter(CodingErrorAction.REPORT);
      var buffer = encoder.encode(java.nio.CharBuffer.wrap(value));
      var bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("审批参数包含无效 Unicode");
    }
  }

  private static int compareCodePoints(String left, String right) {
    var leftPoints = left.codePoints().iterator();
    var rightPoints = right.codePoints().iterator();
    while (leftPoints.hasNext() && rightPoints.hasNext()) {
      int comparison = Integer.compare(leftPoints.nextInt(), rightPoints.nextInt());
      if (comparison != 0) {
        return comparison;
      }
    }
    return Boolean.compare(leftPoints.hasNext(), rightPoints.hasNext());
  }

  private static IllegalArgumentException invalidJson() {
    return new IllegalArgumentException("审批参数 JSON 无效");
  }

  private static final class Parser {
    private final String source;
    private int index;

    private Parser(String source) {
      this.source = source;
    }

    private Object parse() {
      skipWhitespace();
      Object result = value();
      skipWhitespace();
      if (index != source.length()) {
        throw invalidJson();
      }
      return result;
    }

    private Object value() {
      if (index >= source.length()) {
        throw invalidJson();
      }
      return switch (source.charAt(index)) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Map<String, Object> object() {
      index++;
      skipWhitespace();
      var entries = new java.util.LinkedHashMap<String, Object>();
      var keys = new HashSet<String>();
      if (consume('}')) {
        return entries;
      }
      while (true) {
        if (index >= source.length() || source.charAt(index) != '"') {
          throw invalidJson();
        }
        String key = string();
        if (!keys.add(key)) {
          throw invalidJson();
        }
        skipWhitespace();
        require(':');
        skipWhitespace();
        entries.put(key, value());
        skipWhitespace();
        if (consume('}')) {
          return entries;
        }
        require(',');
        skipWhitespace();
      }
    }

    private List<Object> array() {
      index++;
      skipWhitespace();
      var items = new ArrayList<>();
      if (consume(']')) {
        return items;
      }
      while (true) {
        items.add(value());
        skipWhitespace();
        if (consume(']')) {
          return items;
        }
        require(',');
        skipWhitespace();
      }
    }

    private String string() {
      require('"');
      var result = new StringBuilder();
      while (index < source.length()) {
        char current = source.charAt(index++);
        if (current == '"') {
          utf8(result.toString());
          return result.toString();
        }
        if (current == '\\') {
          if (index >= source.length()) {
            throw invalidJson();
          }
          char escaped = source.charAt(index++);
          switch (escaped) {
            case '"', '\\', '/' -> result.append(escaped);
            case 'b' -> result.append('\b');
            case 'f' -> result.append('\f');
            case 'n' -> result.append('\n');
            case 'r' -> result.append('\r');
            case 't' -> result.append('\t');
            case 'u' -> result.append(unicodeEscape());
            default -> throw invalidJson();
          }
        } else {
          if (current < 0x20) {
            throw invalidJson();
          }
          result.append(current);
        }
      }
      throw invalidJson();
    }

    private char unicodeEscape() {
      if (index + 4 > source.length()) {
        throw invalidJson();
      }
      try {
        char decoded = (char) Integer.parseInt(source.substring(index, index + 4), 16);
        index += 4;
        return decoded;
      } catch (NumberFormatException exception) {
        throw invalidJson();
      }
    }

    private Number number() {
      int start = index;
      consume('-');
      if (consume('0')) {
        if (index < source.length() && Character.isDigit(source.charAt(index))) {
          throw invalidJson();
        }
      } else {
        requireDigits();
      }
      boolean decimal = false;
      if (consume('.')) {
        decimal = true;
        requireDigits();
      }
      if (consume('e') || consume('E')) {
        decimal = true;
        if (!consume('+')) {
          consume('-');
        }
        requireDigits();
      }
      String token = source.substring(start, index);
      try {
        return decimal ? new BigDecimal(token) : new BigInteger(token);
      } catch (NumberFormatException exception) {
        throw invalidJson();
      }
    }

    private void requireDigits() {
      int start = index;
      while (index < source.length() && Character.isDigit(source.charAt(index))) {
        index++;
      }
      if (start == index) {
        throw invalidJson();
      }
    }

    private Object literal(String expected, Object value) {
      if (!source.startsWith(expected, index)) {
        throw invalidJson();
      }
      index += expected.length();
      return value;
    }

    private void require(char expected) {
      if (!consume(expected)) {
        throw invalidJson();
      }
    }

    private boolean consume(char expected) {
      if (index < source.length() && source.charAt(index) == expected) {
        index++;
        return true;
      }
      return false;
    }

    private void skipWhitespace() {
      while (index < source.length()) {
        char value = source.charAt(index);
        if (value != ' ' && value != '\n' && value != '\r' && value != '\t') {
          return;
        }
        index++;
      }
    }
  }
}
