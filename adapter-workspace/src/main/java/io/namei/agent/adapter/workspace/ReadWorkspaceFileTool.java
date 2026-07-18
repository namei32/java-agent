package io.namei.agent.adapter.workspace;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Contract definition for the bounded, read-only workspace file projection. */
public final class ReadWorkspaceFileTool implements Tool {
  private static final ToolDefinition DEFINITION =
      new ToolDefinition(
          "read_file",
          "读取显式 Workspace Root 内的受预算 UTF-8 文本文件。",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of(
                  "path", Map.of("type", "string"),
                  "offset", Map.of("type", "integer"),
                  "limit", Map.of("type", "integer")),
              "required",
              java.util.List.of("path"),
              "additionalProperties",
              false),
          ToolRisk.READ_ONLY,
          "workspace-read-only-v1");

  private final Path root;
  private final WorkspaceToolLimits limits;

  public ReadWorkspaceFileTool(Path root, WorkspaceToolLimits limits) {
    this.root = Objects.requireNonNull(root, "root");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  @Override
  public ToolDefinition definition() {
    return DEFINITION;
  }

  @Override
  public ToolResult execute(Map<String, Object> arguments) {
    try {
      WorkspaceToolPath path =
          WorkspaceToolArguments.requiredPath(arguments, Set.of("path", "offset", "limit"));
      int offset = WorkspaceToolArguments.optionalInteger(arguments, "offset", 0, 0);
      int requestedLimit =
          WorkspaceToolArguments.optionalInteger(arguments, "limit", limits.maxLines(), 1);
      WorkspacePathResolver.ResolvedPath resolved = WorkspacePathResolver.open(root).resolve(path);
      BasicFileAttributes attributes = resolved.initialAttributes();
      if (!attributes.isRegularFile()) {
        return error(WorkspaceToolError.WORKSPACE_PATH_REJECTED);
      }
      if (attributes.size() > limits.maxSourceBytes()) {
        return error(WorkspaceToolError.WORKSPACE_BUDGET_EXCEEDED);
      }
      byte[] bytes = readBounded(resolved.path(), limits.maxSourceBytes());
      resolved.verifyUnchanged();
      String text = decodeUtf8(bytes);
      if (text.indexOf('\u0000') >= 0) {
        return error(WorkspaceToolError.WORKSPACE_NOT_TEXT);
      }
      return ToolResult.success(render(text, offset, requestedLimit, limits));
    } catch (WorkspaceToolContractException violation) {
      return error(violation.code());
    } catch (IOException | RuntimeException failure) {
      return error(WorkspaceToolError.WORKSPACE_PATH_REJECTED);
    }
  }

  private static byte[] readBounded(Path path, int maximum) throws IOException {
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8_192))) {
      byte[] buffer = new byte[Math.min(maximum + 1, 8_192)];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > maximum) {
          throw WorkspaceToolError.WORKSPACE_BUDGET_EXCEEDED.violation();
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw WorkspaceToolError.WORKSPACE_NOT_TEXT.violation();
    }
  }

  private static String render(
      String raw, int offset, int requestedLimit, WorkspaceToolLimits limits) {
    List<String> lines = List.of(raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    if (offset >= lines.size()) {
      return "无可显示行。";
    }
    int requestedEnd = Math.min(lines.size(), safeEnd(offset, requestedLimit));
    int budgetEnd = Math.min(requestedEnd, safeEnd(offset, limits.maxLines()));
    boolean truncated = budgetEnd < requestedEnd;
    var projection = new ArrayList<String>();
    for (int index = offset; index < budgetEnd; index++) {
      String candidate = (index + 1) + ": " + lines.get(index);
      if (!fits(join(projection, candidate), limits)) {
        truncated = true;
        break;
      }
      projection.add(candidate);
    }
    if (projection.size() < budgetEnd - offset) {
      truncated = true;
    }
    if (truncated) {
      appendTruncation(projection, limits);
    }
    return projection.isEmpty()
        ? WorkspaceToolLimits.TRUNCATION_MARKER
        : String.join("\n", projection);
  }

  private static int safeEnd(int offset, int count) {
    return count > Integer.MAX_VALUE - offset ? Integer.MAX_VALUE : offset + count;
  }

  private static void appendTruncation(List<String> projection, WorkspaceToolLimits limits) {
    while (!projection.isEmpty()
        && !fits(join(projection, WorkspaceToolLimits.TRUNCATION_MARKER), limits)) {
      projection.removeLast();
    }
    if (fits(join(projection, WorkspaceToolLimits.TRUNCATION_MARKER), limits)) {
      projection.add(WorkspaceToolLimits.TRUNCATION_MARKER);
    }
  }

  private static String join(List<String> existing, String next) {
    return existing.isEmpty() ? next : String.join("\n", existing) + "\n" + next;
  }

  private static boolean fits(String value, WorkspaceToolLimits limits) {
    return value.getBytes(StandardCharsets.UTF_8).length <= limits.maxOutputBytes()
        && value.codePointCount(0, value.length()) <= limits.maxOutputCodePoints();
  }

  private static ToolResult error(WorkspaceToolError error) {
    return ToolResult.error(error.name());
  }
}
